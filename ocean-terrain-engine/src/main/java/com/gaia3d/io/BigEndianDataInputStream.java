/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.io;

import org.joml.Vector4d;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class BigEndianDataInputStream extends DataInputStream implements DataInput {

    public BigEndianDataInputStream(InputStream in) {
        super(in);
    }

    public byte[] readBytes(int count) throws IOException {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = readByte();
        }
        return bytes;
    }

    public short[] readShorts(int count) throws IOException {
        short[] shorts = new short[count];
        for (int i = 0; i < count; i++) {
            shorts[i] = readShort();
        }
        return shorts;
    }

    public int[] readInts(int count) throws IOException {
        int[] ints = new int[count];
        for (int i = 0; i < count; i++) {
            ints[i] = readInt();
        }
        return ints;
    }

    public float[] readFloats(int count) throws IOException {
        float[] floats = new float[count];
        for (int i = 0; i < count; i++) {
            floats[i] = readFloat();
        }
        return floats;
    }

    public String readText() throws IOException {
        return readUTF();
    }

    public Vector4d readVector4() throws IOException {
        return new Vector4d(readFloat(), readFloat(), readFloat(), readFloat());
    }

    public String readIntAndUTF() throws IOException {
        int length = readInt();
        byte[] bytes = new byte[length];
        if (read(bytes) != length) {
            throw new EOFException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readIntAndUTF(int length) throws IOException {
        byte[] bytes = new byte[length];
        if (read(bytes) != length) {
            throw new EOFException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
