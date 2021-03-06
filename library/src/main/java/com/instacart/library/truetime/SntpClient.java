package com.instacart.library.truetime;

/*
 * Original work Copyright (C) 2008 The Android Open Source Project
 * Modified work Copyright (C) 2016, Instacart
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.os.SystemClock;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Simple SNTP client class for retrieving network time.
 */
public class SntpClient {

    public static final int RESPONSE_INDEX_ORIGINATE_TIME = 0;
    public static final int RESPONSE_INDEX_RECEIVE_TIME = 1;
    public static final int RESPONSE_INDEX_TRANSMIT_TIME = 2;
    public static final int RESPONSE_INDEX_RESPONSE_TIME = 3;
    public static final int RESPONSE_INDEX_ROOT_DELAY = 4;
    public static final int RESPONSE_INDEX_DISPERSION = 5;
    public static final int RESPONSE_INDEX_STRATUM = 6;
    public static final int RESPONSE_INDEX_RESPONSE_TICKS = 7;
    public static final int RESPONSE_INDEX_SIZE = 8;

    private static final String TAG = SntpClient.class.getSimpleName();

    private static final int NTP_PORT = 123;
    private static final int NTP_MODE = 3;
    private static final int NTP_VERSION = 3;
    private static final int NTP_PACKET_SIZE = 48;

    private static final int INDEX_VERSION = 0;
    private static final int INDEX_ROOT_DELAY = 4;
    private static final int INDEX_ROOT_DISPERSION = 8;
    private static final int INDEX_ORIGINATE_TIME = 24;
    private static final int INDEX_RECEIVE_TIME = 32;
    private static final int INDEX_TRANSMIT_TIME = 40;

    // 70 years plus 17 leap days
    private static final long OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L;

    private long _cachedDeviceUptime;
    private long _cachedSntpTime;
    private boolean _sntpInitialized = false;

    /**
     * See δ :
     * https://en.wikipedia.org/wiki/Network_Time_Protocol#Clock_synchronization_algorithm
     */
    public static long getRoundTripDelay(long[] response) {
        return (response[RESPONSE_INDEX_RESPONSE_TIME] - response[RESPONSE_INDEX_ORIGINATE_TIME]) -
               (response[RESPONSE_INDEX_TRANSMIT_TIME] - response[RESPONSE_INDEX_RECEIVE_TIME]);
    }

    /**
     * See θ :
     * https://en.wikipedia.org/wiki/Network_Time_Protocol#Clock_synchronization_algorithm
     */
    public static long getClockOffset(long[] response) {
        return ((response[RESPONSE_INDEX_RECEIVE_TIME] - response[RESPONSE_INDEX_ORIGINATE_TIME]) +
                (response[RESPONSE_INDEX_TRANSMIT_TIME] - response[RESPONSE_INDEX_RESPONSE_TIME])) / 2;
    }

    /**
     * Sends an NTP request to the given host and processes the response.
     *
     * @param ntpHost         host name of the server.
     * @param timeoutInMillis network timeout in milliseconds.
     */
    long[] requestTime(String ntpHost, int timeoutInMillis) throws IOException {

        DatagramSocket socket = null;

        try {

            byte[] buffer = new byte[NTP_PACKET_SIZE];
            InetAddress address = InetAddress.getByName(ntpHost);

            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

            _writeVersion(buffer);

            // -----------------------------------------------------------------------------------
            // get current time and write it to the request packet

            long requestTime = System.currentTimeMillis();
            long requestTicks = SystemClock.elapsedRealtime();

            _writeTimeStamp(buffer, INDEX_TRANSMIT_TIME, requestTime);

            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutInMillis);
            socket.send(request);

            // -----------------------------------------------------------------------------------
            // read the response

            long t[] = new long[RESPONSE_INDEX_SIZE];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            long responseTicks = SystemClock.elapsedRealtime();
            t[RESPONSE_INDEX_RESPONSE_TICKS] = responseTicks;

            // -----------------------------------------------------------------------------------
            // extract the results
            // See here for the algorithm used:
            // https://en.wikipedia.org/wiki/Network_Time_Protocol#Clock_synchronization_algorithm

            long originateTime = _readTimeStamp(buffer, INDEX_ORIGINATE_TIME);     // T0
            long receiveTime = _readTimeStamp(buffer, INDEX_RECEIVE_TIME);         // T1
            long transmitTime = _readTimeStamp(buffer, INDEX_TRANSMIT_TIME);       // T2
            long responseTime = requestTime + (responseTicks - requestTicks);       // T3

            t[RESPONSE_INDEX_ORIGINATE_TIME] = originateTime;
            t[RESPONSE_INDEX_RECEIVE_TIME] = receiveTime;
            t[RESPONSE_INDEX_TRANSMIT_TIME] = transmitTime;
            t[RESPONSE_INDEX_RESPONSE_TIME] = responseTime;

            // -----------------------------------------------------------------------------------
            // check validity of response

            long rootDelay = _read(buffer, INDEX_ROOT_DELAY);
            t[RESPONSE_INDEX_ROOT_DELAY] = rootDelay;
            if (rootDelay > 100) {
                throw new InvalidNtpServerResponseException("Invalid response from NTP server. Root delay violation " +
                                                            rootDelay);
            }

            long rootDispersion = _read(buffer, INDEX_ROOT_DISPERSION);
            t[RESPONSE_INDEX_DISPERSION] = rootDispersion;
            if (rootDispersion > 100) {
                throw new InvalidNtpServerResponseException(
                      "Invalid response from NTP server. Root dispersion violation " + rootDispersion);
            }

            final byte mode = (byte) (buffer[0] & 0x7);
            if (mode != 4 && mode != 5) {
                throw new InvalidNtpServerResponseException("untrusted mode value for TrueTime: " + mode);
            }

            final int stratum = buffer[1] & 0xff;
            t[RESPONSE_INDEX_STRATUM] = stratum;
            if (stratum < 1 || stratum > 15) {
                throw new InvalidNtpServerResponseException("untrusted stratum value for TrueTime: " + stratum);
            }

            final byte leap = (byte) ((buffer[0] >> 6) & 0x3);
            if (leap == 3) {
                throw new InvalidNtpServerResponseException("unsynchronized server responded for TrueTime");
            }

            long delay = Math.abs((responseTime - originateTime) - (transmitTime - receiveTime));
            if (delay >= 100) {
                throw new InvalidNtpServerResponseException("Server response delay too large for comfort " + delay);
            }

            long timeElapsedSinceRequest = Math.abs(originateTime - System.currentTimeMillis());
            if (timeElapsedSinceRequest >= 10_000) {
                throw new InvalidNtpServerResponseException("Request was sent more than 10 seconds back " +
                                                            timeElapsedSinceRequest);
            }

            _sntpInitialized = true;
            TrueLog.i(TAG, "---- SNTP successful response from " + ntpHost);

            // -----------------------------------------------------------------------------------
            // TODO:
            cacheTrueTimeInfo(t);
            return t;

        } catch (Exception e) {
            TrueLog.d(TAG, "---- SNTP request failed for " + ntpHost);
            throw e;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    void cacheTrueTimeInfo(long[] response) {
        _cachedSntpTime = sntpTime(response);
        _cachedDeviceUptime = response[RESPONSE_INDEX_RESPONSE_TICKS];
    }

    long sntpTime(long[] response) {
        long clockOffset = getClockOffset(response);
        long responseTime = response[RESPONSE_INDEX_RESPONSE_TIME];
        return responseTime + clockOffset;
    }

    boolean wasInitialized() {
        return _sntpInitialized;
    }

    /**
     * @return time value computed from NTP server response
     */
    long getCachedSntpTime() {
        return _cachedSntpTime;
    }

    /**
     * @return device uptime computed at time of executing the NTP request
     */
    long getCachedDeviceUptime() {
        return _cachedDeviceUptime;
    }

    // -----------------------------------------------------------------------------------
    // private helpers

    /**
     * Writes NTP version as defined in RFC-1305
     */
    private void _writeVersion(byte[] buffer) {
        // mode is in low 3 bits of first byte
        // version is in bits 3-5 of first byte
        buffer[INDEX_VERSION] = NTP_MODE | (NTP_VERSION << 3);
    }

    /**
     * Writes system time (milliseconds since January 1, 1970)
     * as an NTP time stamp as defined in RFC-1305
     * at the given offset in the buffer
     */
    private void _writeTimeStamp(byte[] buffer, int offset, long time) {

        long seconds = time / 1000L;
        long milliseconds = time - seconds * 1000L;

        // consider offset for number of seconds
        // between Jan 1, 1900 (NTP epoch) and Jan 1, 1970 (Java epoch)
        seconds += OFFSET_1900_TO_1970;

        // write seconds in big endian format
        buffer[offset++] = (byte) (seconds >> 24);
        buffer[offset++] = (byte) (seconds >> 16);
        buffer[offset++] = (byte) (seconds >> 8);
        buffer[offset++] = (byte) (seconds >> 0);

        long fraction = milliseconds * 0x100000000L / 1000L;

        // write fraction in big endian format
        buffer[offset++] = (byte) (fraction >> 24);
        buffer[offset++] = (byte) (fraction >> 16);
        buffer[offset++] = (byte) (fraction >> 8);

        // low order bits should be random data
        buffer[offset++] = (byte) (Math.random() * 255.0);
    }

    /**
     * @param offset offset index in buffer to start reading from
     * @return NTP timestamp in Java epoch
     */
    private long _readTimeStamp(byte[] buffer, int offset) {
        long seconds = _read(buffer, offset);
        long fraction = _read(buffer, offset + 4);

        return ((seconds - OFFSET_1900_TO_1970) * 1000) + ((fraction * 1000L) / 0x100000000L);
    }

    /**
     * @return 4 bytes as a 32-bit long (unsigned big endian)
     */
    private long _read(byte[] buffer, int offset) {
        byte b0 = buffer[offset];
        byte b1 = buffer[offset + 1];
        byte b2 = buffer[offset + 2];
        byte b3 = buffer[offset + 3];

        return ((long) ui(b0) << 24) +
               ((long) ui(b1) << 16) +
               ((long) ui(b2) << 8) +
               (long) ui(b3);
    }

    /***
     * Convert byte to unsigned int.
     *
     * Java only has signed types so we have to do
     * more work to get unsigned ops
     *
     * @param b input byte
     * @return unsigned int value
     */
    private int ui(byte b) {
        return (b & 0x80) == 0x80 ? (b & 0x7F) + 0x80 : b;
    }
}
