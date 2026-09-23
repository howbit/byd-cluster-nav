package com.byd.clusternav.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AdbProtocol {
    public static final int CMD_SYNC = 0x434e5953;
    public static final int CMD_CNXN = 0x4e584e43;
    public static final int CMD_AUTH = 0x48545541;
    public static final int CMD_OPEN = 0x4e45504f;
    public static final int CMD_OKAY = 0x59414b4f;
    public static final int CMD_CLSE = 0x45534c43;
    public static final int CMD_WRTE = 0x45545257;

    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;

    public static final int ADB_VERSION = 0x01000000;
    public static final int MAX_DATA = 4096;

    public static class AdbMessage {
        public int command;
        public int arg0;
        public int arg1;
        public int dataLength;
        public int dataCrc32;
        public int magic;
        public byte[] data;

        public AdbMessage(int command, int arg0, int arg1, int dataLength, int dataCrc32, int magic, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.dataLength = dataLength;
            this.dataCrc32 = dataCrc32;
            this.magic = magic;
            this.data = data != null ? data : new byte[0];
        }

        public byte[] toByteArray() {
            ByteBuffer buf = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(command);
            buf.putInt(arg0);
            buf.putInt(arg1);
            buf.putInt(data.length);
            buf.putInt(calculateChecksum(data));
            buf.putInt(command ^ -1);
            if (data.length > 0) {
                buf.put(data);
            }
            return buf.array();
        }
    }

    public static int calculateChecksum(byte[] data) {
        int checksum = 0;
        if (data != null) {
            for (byte b : data) {
                checksum += (b & 0xFF);
            }
        }
        return checksum;
    }

    public static AdbMessage parseMessage(byte[] headerBytes, byte[] dataBytes) {
        ByteBuffer buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int dataLength = buf.getInt();
        int dataCrc32 = buf.getInt();
        int magic = buf.getInt();
        return new AdbMessage(command, arg0, arg1, dataLength, dataCrc32, magic, dataBytes);
    }
}
