package msp;

import arc.files.Fi;
import mindustry.ui.dialogs.BaseDialog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public interface ISyncContext {
    /**
     * To detect the side.
     */
    boolean isHost();

    /**
     * Can we expect any changes in the answer?
     */
    boolean isStatic();

    default boolean hasData() { return true; }

    BaseDialog getDialog();

    void write(byte b) throws IOException;
    void write(int b) throws IOException;

    default void write(final byte[] arr) throws IOException {
        for (final byte b : arr)
            write(b);
    }

    default void write(final byte[] arr, int off, int len) throws IOException {
        for (len += off; off < len; off++)
            write(arr[off]);
    }

    default void write(final String str) throws IOException {
        write(str.getBytes(StandardCharsets.UTF_8));
    }

    default void writeString(final String str) throws IOException {
        final byte[] d = str.getBytes(StandardCharsets.UTF_8);
        if (d.length > 255)
            throw new RuntimeException("String Length is more than 255");
        write(d.length);
        write(d);
    }

    default void writeInt(final int value) throws IOException {
        write((byte)(value >>> 24));
        write((byte)(value >>> 16));
        write((byte)(value >>> 8));
        write((byte)value);
    }

    default void sendFile(final Fi file) throws IOException {
        final byte[] d = file.readBytes();
        writeInt(d.length);
        write(d);
    }

    void flush() throws IOException;

    byte readByte() throws IOException;

    byte[] readBuf(final byte[] buf, int off, int len) throws IOException;

    default byte[] readBuf(final int len) throws IOException { return readBuf(new byte[len], 0, len); }

    default boolean tryRead(final byte[] data) throws IOException {
        for (final byte b : data)
            if (b != readByte())
                return false;
        return true;
    }

    default int readInt() throws IOException {
        return ((readByte() & 0xFF) << 24) |
                ((readByte() & 0xFF) << 16) |
                ((readByte() & 0xFF) << 8 ) |
                (readByte() & 0xFF);
    }

    default UUID readUUID() throws IOException { return UUID.nameUUIDFromBytes(readBuf(16)); }
    default String readString() throws IOException { return new String(readBuf(readByte()), StandardCharsets.UTF_8); }

    default void readFile(final OutputStream o) throws IOException {
        if (o == null) {
            readBuf(readInt());
            return;
        }
        o.write(readBuf(readInt()));
    }
}