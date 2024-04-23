package msp.base;

import arc.ApplicationListener;
import arc.Core;
import arc.files.Fi;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.TextButton;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import msp.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalSyncService implements ISyncService {
    public static final byte[]
            NET_ID = { -124, 54, 35, 83, -98, 93, 48, -101, 93, -8, 54, 93, 48, 54, 17, -92 },
            VER    = { 0, 0, 0, 0 };

    public static final int BUFF_SIZE = 1024 * 1024; // 1KB

    public final String ip = "230.0.0.0";
    public final int port = 34554;

    public final SyncVar<Exception> ex = new SyncVar<>();

    public final byte[] deviceId;
    public final SyncVar<String> deviceName;

    public Thread server, server2;
    public final SyncVar<MulticastSocket> serverSocket = new SyncVar<>();
    public final SyncVar<ServerSocket> tcpServer = new SyncVar<>();

    private final AtomicBoolean acceptJustSync = new AtomicBoolean(false);

    private final Object locker = new Object();
    private Runnable onClose = null;

    private final Object onCode0Locker = new Object();
    private final ArrayList<Device> onCode0List = new ArrayList<>();
    private onDevicePacket onCode0 = null;
    private onDevicePacket onCode1 = null;

    private final Map<String, ISyncData> dm;

    private interface onDevicePacket { void run(final Device device); }

    private static class Device {
        public final String platform, name, uuid;
        public final InetAddress ip;

        public Device(final String uuid, final String platform, final String name, final InetAddress ip) {
            this.uuid = uuid;
            this.platform = platform;
            this.name = name;
            this.ip = ip;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj instanceof String)
                return uuid.equals(obj);
            if (obj instanceof Device)
                return uuid.equals(((Device) obj).uuid);
            return super.equals(obj);
        }
    }

    private static class DeviceButton extends TextButton {
        public final Device device;

        public DeviceButton(final Device device, final Runnable runnable) {
            super("[" + device.platform + "] " + device.name);
            if (runnable != null)
                changed(runnable);
            this.device = device;
        }
    }

    private static class OutputWriter implements Closeable {
        public final byte[] buf = new byte[BUFF_SIZE];
        public final OutputStream os;
        public int i = 0;

        public OutputWriter(final OutputStream os) { this.os = os; }

        public void write(byte b) { buf[i++] = b; }
        public void write(int b) { buf[i++] = (byte) b; }

        public void write(final byte[] arr) {
            for (final byte b : arr)
                buf[i++] = b;
        }

        public void write(final byte[] arr, int off, int len) {
            for (len += off; off < len; off++)
                write(off);
        }

        public void write(final String str) {
            write(str.getBytes(StandardCharsets.UTF_8));
        }

        public void writeString(final String str) {
            final byte[] d = str.getBytes(StandardCharsets.UTF_8);
            if (d.length > 255)
                throw new RuntimeException("String Length is more than 255");
            write(d.length);
            write(d);
        }

        public void writeInt(final int value) {
            buf[i++] = (byte)(value >>> 24);
            buf[i++] = (byte)(value >>> 16);
            buf[i++] = (byte)(value >>> 8);
            buf[i++] = (byte)value;
        }

        public void sendFile(final Fi file) throws IOException {
            flush();
            try (final InputStream r = file.read()) {
                int l;
                while ((l = r.read(buf, 4, buf.length - 4)) != -1) {
                    writeInt(l);
                    i += l;
                    flush();
                }
                writeInt(-1);
                flush();
            }
        }

        public void flush() throws IOException {
            os.write(buf, 0, i);
            os.flush();
            i = 0;
        }

        @Override public void close() throws IOException { os.close(); }
    }

    private static class InputReader implements Closeable {
        public final byte[] buf = new byte[BUFF_SIZE];
        private final InputStream is;
        private int i = 0, l = 0;

        public InputReader(final InputStream is) { this.is = is; }

        public byte readByte() throws IOException {
            if (i == l) {
                l = is.read(buf, i = 0, buf.length);
                if (l == -1)
                    throw new IOException("Negative length!");
            }
            return buf[i++];
        }

        public byte[] readBuf(final byte[] buf, int off, int len) throws IOException {
            for (len += off; off < len; off++)
                buf[off] = readByte();
            return buf;
        }

        public byte[] readBuf(final int len) throws IOException { return readBuf(new byte[len], 0, len); }

        public boolean tryRead(final byte[] data) throws IOException {
            for (final byte b : data)
                if (b != readByte())
                    return false;
            return true;
        }

        public int readInt() throws IOException {
            return ((readByte() & 0xFF) << 24) |
                    ((readByte() & 0xFF) << 16) |
                    ((readByte() & 0xFF) << 8 ) |
                    (readByte() & 0xFF);
        }

        public UUID readUUID() throws IOException {
            return UUID.nameUUIDFromBytes(readBuf(16));
        }

        public String readString() throws IOException {
            return new String(readBuf(readByte()), StandardCharsets.UTF_8);
        }

        public void readFile(final OutputStream o) throws IOException {
            int s;
            while (true) {
                s = readInt();
                if (s == -1)
                    break;
                if (s < -1)
                    throw new IOException("Negative part size: " + s);
                while (s > 0) {
                    final int d = l - i;
                    if (s >= d) {
                        s -= d;
                        if (o != null) {
                            o.write(buf, i, d);
                            o.flush();
                        }

                        l = is.read(buf, i = 0, buf.length);
                        if (l == -1)
                            throw new IOException("Negative length!");
                        continue;
                    }
                    if (o != null) {
                        o.write(buf, i, s);
                        o.flush();
                    }
                    i += s;
                    break;
                }
            }
        }

        @Override public void close() throws IOException { is.close(); }
    }

    public LocalSyncService(final MindustrySyncProject msp) {
        deviceId = msp.deviceId.get();
        deviceName = msp.deviceName;
        dm = msp.syncDataList;

        server2 = new Thread(() -> {
            final AtomicBoolean cp = new AtomicBoolean(false);
            try (final ServerSocket s = new ServerSocket()) {
                tcpServer.set(s);
                s.bind(new InetSocketAddress("0.0.0.0", port));
                while (true) {
                    final Socket c = s.accept();
                    new Thread(() -> {
                        final SyncVar<ABaseDialog> window = new SyncVar<>();
                        final AtomicBoolean syncing = new AtomicBoolean(false);
                        try (c) {
                            c.setSoTimeout(5000);
                            final OutputWriter w = new OutputWriter(c.getOutputStream());
                            final InputReader r = new InputReader(c.getInputStream());
                            if (!r.tryRead(NET_ID) || !r.tryRead(VER))
                                return;
                            byte o = r.readByte();
                            switch (o) {
                                case 3:
                                    final UUID uuid = r.readUUID();
                                    final String name = r.readString();

                                    if (!acceptJustSync.get()) {
                                        w.write(2);
                                        w.writeString("sync-rejected");
                                        w.flush();
                                        break;
                                    }

                                    synchronized (cp) {
                                        if (cp.get()) {
                                            w.write(2);
                                            w.writeString("server.kicked.playerLimit");
                                            w.flush();
                                            break;
                                        }
                                        cp.set(true);
                                        window.set(new ABaseDialog("@sync-request"));
                                    }

                                    final AtomicBoolean
                                            a = new AtomicBoolean(false),
                                            f = new AtomicBoolean(false);

                                    synchronized (f) {
                                        Core.app.post(() -> {
                                            final ABaseDialog d = window.get();
                                            d.cont.defaults().size(280f, 60f);
                                            d.cont.add(Core.bundle.get("sync-device-name") + ": " + name).row();
                                            d.cont.add("UUID: " + uuid).row();
                                            d.cont.button("@sync-reject", Icon.exit, () -> {
                                                synchronized (f) {
                                                    f.set(true);
                                                    f.notifyAll();
                                                }
                                                d.hide();
                                            });
                                            d.cont.button("@sync-accept", Icon.ok, () -> {
                                                window.set(new ABaseDialog("@syncing"));
                                                synchronized (f) {
                                                    a.set(true);
                                                    f.set(true);
                                                    f.notifyAll();
                                                }
                                                window.get().show();
                                                d.hide();
                                            }).row();
                                            d.show();
                                        });

                                        c.setSoTimeout(20000);
                                        while (true) {
                                            f.wait(15000);
                                            if (f.get()) {
                                                if (a.get()) {
                                                    w.write(3);
                                                    w.flush();
                                                    final byte b = r.readByte();
                                                    if (b != 3) {
                                                        final String text = b == 2 ? Core.bundle.get(r.readString()) : Core.bundle.format("sync-unknown-code", b);
                                                        cp.set(false);
                                                        Core.app.post(() -> {
                                                            window.get().hide();
                                                            Vars.ui.showInfo(text);
                                                        });
                                                        break;
                                                    }
                                                    Core.app.post(() -> window.get().cont.defaults().size(280f, 60f));
                                                    syncing.set(true);
                                                    sync(w, r, window.get(), true);
                                                } else {
                                                    w.write(2);
                                                    w.writeString("sync-rejected");
                                                    w.flush();
                                                }
                                                cp.set(false);
                                                break;
                                            }
                                            w.write(4);
                                            w.flush();

                                            final byte b = r.readByte();
                                            if (b == 4)
                                                continue;
                                            if (b == 2)
                                                break;
                                            w.write(2);
                                            w.writeString("sync-unknown-code");
                                            w.write(b);
                                            w.flush();
                                            break;
                                        }
                                    }
                                    break;
                                default:
                                    w.write(2);
                                    w.writeString("sync-unknown-code");
                                    w.write(o);
                                    w.flush();
                                    break;
                            }
                        } catch (final Exception ex) {
                            Log.err(ex);
                            if (window.get() != null)
                                Core.app.post(() -> {
                                    window.get().hide();
                                    window.set(null);
                                    cp.set(false);
                                    Vars.ui.showException(ex);
                                });
                        }
                    }).start();
                }
            } catch (final Exception ex) {
                if (ex.getMessage().equals("Socket closed"))
                    return;
                server.interrupt();
                final MulticastSocket s = serverSocket.get();
                if (s != null)
                    s.close();
                synchronized (this.ex.locker) {
                    if (this.ex.get() == null)
                        this.ex.set(ex);
                }
                Log.err(ex);
            }
        });
        server = new Thread(() -> {
            while (true)
                try (final MulticastSocket socket = new MulticastSocket(port)) {
                    serverSocket.set(socket);
                    socket.setReuseAddress(false);
                    InetSocketAddress group = new InetSocketAddress(ip, port);
                    socket.joinGroup(group, null);
                    final byte[] buf = new byte[295];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    if (NetworkInterface.getByInetAddress(packet.getAddress()) != null)
                        continue;

                    if (packet.getLength() < 21)
                        continue;

                    if (
                            eq(packet.getData(), packet.getOffset(), NET_ID) &&
                                    eq(packet.getData(), 16 + packet.getOffset(), VER)
                    ) {
                        if (packet.getData()[20 + packet.getOffset()] == 0) {
                            if (packet.getLength() < 40)
                                continue;
                            final onDevicePacket l;
                            synchronized (onCode0Locker) {
                                if (onCode0 == null)
                                    continue;
                                l = onCode0;
                            }

                            final String uuid = UUID.nameUUIDFromBytes(Arrays.copyOfRange(packet.getData(), 21 + packet.getOffset(), 37 + packet.getOffset())).toString();
                            final Device device = new Device(
                                    uuid,
                                    switch (packet.getData()[37 + packet.getOffset()]) {
                                        case 1 -> "desktop";
                                        case 2 -> "android";
                                        case 3 -> "ios";
                                        default -> "unknown";
                                    },
                                    new String(packet.getData(), 39 + packet.getOffset(), ((int) packet.getData()[38 + packet.getOffset()]) + 1, StandardCharsets.UTF_8),
                                    packet.getAddress()
                            );
                            synchronized (onCode0List) {
                                if (onCode0List.contains(device))
                                    continue;
                                onCode0List.add(device);
                                l.run(device);
                            }
                        } else if (packet.getData()[20 + packet.getOffset()] == 1) {
                            if (packet.getLength() < 37)
                                continue;
                            final onDevicePacket l;
                            synchronized (onCode0Locker) {
                                if (onCode1 == null)
                                    continue;
                                l = onCode1;
                            }
                            final String uuid = UUID.nameUUIDFromBytes(Arrays.copyOfRange(packet.getData(), 21 + packet.getOffset(), 37 + packet.getOffset())).toString();
                            synchronized (onCode0List) {
                                for (final Device d : onCode0List)
                                    if (d.uuid.equals(uuid)) {
                                        onCode0List.remove(d);
                                        l.run(d);
                                        break;
                                    }
                            }
                        }
                    }
                    socket.leaveGroup(group, null);
                } catch (final Exception ex) {
                    if (ex.getMessage().equals("Socket closed"))
                        break;
                    server2.interrupt();
                    final ServerSocket t = tcpServer.get();
                    if (t != null)
                        try {
                            t.close();
                        } catch (final IOException ex2) {
                            Log.err(ex2);
                        }
                    synchronized (this.ex.locker) {
                        if (this.ex.get() == null)
                            this.ex.set(ex);
                    }
                    Log.err(ex);
                    break;
                }
        });
        server.start();
        server2.start();
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void exit() {
                server.interrupt();
                server2.interrupt();
                final MulticastSocket s = serverSocket.get();
                if (s != null)
                    s.close();
                final ServerSocket t = tcpServer.get();
                if (t != null)
                    try {
                        t.close();
                    } catch (final IOException ex) {
                        Log.err(ex);
                    }
                synchronized (locker) {
                    if (onClose != null)
                        onClose.run();
                }
                try {
                    server.join();
                    server2.join();
                } catch (final InterruptedException ex) {
                    Log.err(ex);
                }
            }
        });
    }

    @Override public Drawable getIcon() { return Icon.upload; }

    private static class LSSCtx implements ISyncContext {
        private final boolean h;
        private final BaseDialog d;
        private final InputReader r;
        private final OutputWriter w;

        public LSSCtx(final boolean host, final InputReader reader, final OutputWriter writer, final BaseDialog dialog) {
            h = host;
            r = reader;
            w = writer;
            d = dialog;
        }

        @Override public boolean isHost() { return h; }
        @Override public boolean isStatic() { return false; }

        @Override public BaseDialog getDialog() { return d; }

        @Override public void write(final byte b) { w.write(b); }
        @Override public void write(final int b) { w.write(b); }
        @Override public void write(final byte[] arr) { w.write(arr); }
        @Override public void write(final byte[] arr, final int off, final int len) { w.write(arr, off, len); }
        @Override public void write(final String str) { w.write(str); }
        @Override public void writeString(final String str) { w.writeString(str); }
        @Override public void writeInt(final int value) { w.writeInt(value); }
        @Override public void sendFile(final Fi file) throws IOException { w.sendFile(file); }
        @Override public void flush() throws IOException { w.flush(); }

        @Override public byte readByte() throws IOException { return r.readByte(); }
        @Override public byte[] readBuf(final byte[] buf, final int off, final int len) throws IOException
                            { return r.readBuf(buf, off, len); }
        @Override public byte[] readBuf(final int len) throws IOException { return r.readBuf(len); }
        @Override public boolean tryRead(final byte[] data) throws IOException { return r.tryRead(data); }
        @Override public int readInt() throws IOException { return r.readInt(); }
        @Override public UUID readUUID() throws IOException { return r.readUUID(); }
        @Override public String readString() throws IOException { return r.readString(); }
        @Override public void readFile(final OutputStream o) throws IOException { r.readFile(o); }
    }

    public void sync(final OutputWriter w, final InputReader r, final ABaseDialog d, final boolean host) throws IOException {
        final LSSCtx ctx = new LSSCtx(host, r, w, d);

        if (host) {
            for (final String dk : dm.keySet())
                w.writeString(dk);
            w.writeString("");
            w.flush();

            while (true) {
                final String k = r.readString();
                if (k.isEmpty())
                    break;
                final ISyncData sd = dm.get(k);
                if (sd == null)
                    continue;
                sd.sync(ctx);
            }
        } else {
            final ArrayList<String> kl = new ArrayList<>();
            while (true) {
                final String k = r.readString();
                if (k.isEmpty())
                    break;
                kl.add(k);
            }
            for (final String k : kl) {
                final ISyncData sd = dm.get(k);
                if (sd == null)
                    continue;
                w.writeString(k);
                w.flush();
                sd.sync(ctx);
            }
            w.writeString("");
            w.flush();
        }

        Core.app.post(() -> {
            d.cont.add("@completed").row();
            d.addCloseButton();
        });
    }

    @Override
    public void settings() {
        final BaseDialog d = new BaseDialog("Local Sync Service");
        d.addCloseButton();

        d.cont.defaults().size(280f, 60f);

        {
            final Exception ex = this.ex.get();
            if (ex != null) {
                d.cont.add("@sync-error").row();
                d.cont.add(ex.toString()).row();
                return;
            }
        }

        d.cont.button("@sync-just-sync", () -> {
            final ABaseDialog f = new ABaseDialog("@sync-just-sync");
            f.cont.defaults().size(280f, 60f);
            f.cont.add("@sync-warn").row();
            f.addCloseButton();
            acceptJustSync.set(true);
            synchronized (locker) {
                f.onClose(onClose = () -> {
                    synchronized (locker) {
                        onClose = null;
                    }
                    acceptJustSync.set(false);
                    synchronized (onCode0Locker) {
                        onCode0 = null;
                        onCode1 = null;
                    }
                    try (final DatagramSocket s = new DatagramSocket()) {
                        s.setReuseAddress(false);
                        s.send(hidePacket());
                    } catch (final Exception ex) {
                        Log.err(ex);
                    }
                });
            }
            f.show();
            synchronized (onCode0Locker) {
                onCode0 = device -> {
                    try (final DatagramSocket s = new DatagramSocket()) {
                        s.setReuseAddress(false);
                        s.send(justFind(device.ip));
                    } catch (final Exception ex) {
                        Log.err(ex);
                    }
                    Core.app.post(() -> f.cont.add(new DeviceButton(device, () -> {
                        final SyncVar<ABaseDialog> r = new SyncVar<>(new ABaseDialog("@connecting"));
                        r.get().show();
                        new Thread(() -> {
                            try (final Socket c = new Socket()) {
                                c.setSoTimeout(5000);
                                c.connect(new InetSocketAddress(device.ip, port), 5000);
                                final OutputWriter w = new OutputWriter(c.getOutputStream());
                                final InputReader i = new InputReader(c.getInputStream());

                                final AtomicBoolean cancel = new AtomicBoolean(false);

                                Core.app.post(() -> {
                                    r.get().title.setText("@sync-requesting");
                                    r.get().onClose(() -> {
                                        synchronized (cancel) {
                                            if (cancel.get())
                                                return;
                                            cancel.set(true);

                                            try {
                                                w.write(2);
                                                w.writeString("canceled");
                                                w.flush();

                                                w.close();
                                            } catch (final IOException ex) {
                                                Log.err(ex);
                                            }
                                        }
                                        try {
                                            i.close();
                                        } catch (final IOException ex) {
                                            Log.err(ex);
                                        }
                                    });
                                    r.get().addCloseButton();
                                });

                                w.write(NET_ID);
                                w.write(VER);

                                w.write(3);
                                w.write(deviceId);
                                w.writeString(deviceName.get());

                                w.flush();

                                c.setSoTimeout(20000);

                                while (true) {
                                    final byte b = i.readByte();

                                    if (b == 4) {
                                        w.write(4);
                                        w.flush();
                                        continue;
                                    }

                                    if (b == 3) {
                                        synchronized (cancel) {
                                            if (cancel.get())
                                                break;
                                            cancel.set(true);

                                            Core.app.post(() -> {
                                                r.get().hide();
                                                r.set(new ABaseDialog("@syncing"));
                                                r.get().show();
                                                synchronized (cancel) {
                                                    cancel.notifyAll();
                                                }
                                            });
                                            cancel.wait();
                                        }
                                        w.write(3);
                                        w.flush();
                                        sync(w, i, r.get(), false);
                                        break;
                                    }

                                    if (b == 2) {
                                        final String reason = readReason(i);
                                        Core.app.post(() -> {
                                            r.get().removeAllCloseListeners();
                                            r.get().title.setText("@sync-rejected");
                                            r.get().cont.add(reason).row();
                                        });
                                        break;
                                    }

                                    Log.err("Unknown code: " + b);
                                    w.write(2);
                                    w.writeString("sync-unknown-code");
                                    w.write(b);
                                    w.flush();
                                    Core.app.post(() -> {
                                        r.get().cont.add(Core.bundle.format("sync-unknown-code", b)).row();
                                        r.get().addCloseButton();
                                    });
                                    break;
                                }
                            } catch (final Exception ex) {
                                Log.err(ex);
                                Core.app.post(() -> {
                                    r.get().hide();
                                    Vars.ui.showException(ex);
                                });
                            }
                        }).start();
                    })).row());
                };
                onCode1 = device -> {
                    for (final Element e : f.cont.getChildren())
                        if (e instanceof DeviceButton && ((DeviceButton) e).device.equals(device)) {
                            f.cont.removeChild(e);
                            break;
                        }
                };
                for (final Device device : onCode0List)
                    onCode0.run(device);
            }
            try (final MulticastSocket socket = new MulticastSocket()) {
                socket.setReuseAddress(false);
                socket.send(justFind(InetAddress.getByName(ip)));
            } catch (final Exception ex) {
                Log.err(ex);
            }
        }).row();

        /*d.cont.add("Trusted devices").row();

        d.cont.button("Add the device", () -> {
            new Thread(() -> {
                try (final MulticastSocket socket = new MulticastSocket()) {
                    socket.setReuseAddress(false);
                    final byte[] name = deviceName.get().getBytes(StandardCharsets.UTF_8);

                    // NET_ID (16) + VER (4) + CODE (1) + DEVICE_ID (16) + PLATFORM (1) + NAME_LENGTH + (1) + NAME (1-256)
                    final byte[] buf = new byte[39 + name.length];
                    paste(buf, 0, NET_ID);
                    paste(buf, 16, VER);
                    buf[20] = 0;
                    paste(buf, 21, deviceId);

                    // Platform
                    if (Core.app.isDesktop())
                        buf[37] = 1;
                    else if (Core.app.isAndroid())
                        buf[37] = 2;
                    else if (Core.app.isIOS())
                        buf[37] = 3;
                    else
                        buf[37] = 0;

                    buf[38] = (byte) (name.length - 1);
                    paste(buf, 39, name);

                    DatagramPacket packet = new DatagramPacket(buf, buf.length, InetAddress.getByName(ip), port);
                    socket.send(packet);
                } catch (final Exception ex) {
                    ex.printStackTrace();
                }
            }).start();
        }).row();*/

        d.show();
    }

    @Override
    public String getName() {
        return "Local Sync Service";
    }

    private boolean eq(final byte[] data, int from, final byte[] search) {
        if (data.length < from + search.length)
            return false;
        for (int i = 0; i < search.length; from++, i++)
            if (data[from] != search[i])
                return false;
        return true;
    }

    private void paste(final byte[] data, int from, final byte[] sub) {
        if (data.length < from + sub.length)
            throw new RuntimeException("LENGTH more than MAX_LENGTH");
        for (int i = 0; i < sub.length; i++, from++)
            data[from] = sub[i];
    }

    private String readReason(final InputReader reader) throws IOException {
        final String r = reader.readString();
        switch (r) {
            case "sync-rejected":
                return Core.bundle.get(r);
            case "sync-unknown-code":
                return Core.bundle.format(r, reader.readByte());
        }
        return Core.bundle.format("sync-unknown-reason", r);
    }

    private DatagramPacket justFind(final InetAddress addr) {
        final byte[] name = deviceName.get().getBytes(StandardCharsets.UTF_8);

        // NET_ID (16) + VER (4) + CODE (1) + DEVICE_ID (16) + PLATFORM (1) + NAME_LENGTH + (1) + NAME (1-256)
        final byte[] buf = new byte[39 + name.length];
        paste(buf, 0, NET_ID);
        paste(buf, 16, VER);
        buf[20] = 0;
        paste(buf, 21, deviceId);

        // Platform
        if (Core.app.isDesktop())
            buf[37] = 1;
        else if (Core.app.isAndroid())
            buf[37] = 2;
        else if (Core.app.isIOS())
            buf[37] = 3;
        else
            buf[37] = 0;

        buf[38] = (byte) (name.length - 1);
        paste(buf, 39, name);

        return new DatagramPacket(buf, buf.length, addr, port);
    }

    private DatagramPacket hidePacket() throws UnknownHostException {
        // NET_ID (16) + VER (4) + CODE (1) + DEVICE_ID (16)
        final byte[] buf = new byte[37];
        paste(buf, 0, NET_ID);
        paste(buf, 16, VER);
        buf[20] = 1;
        paste(buf, 21, deviceId);

        return new DatagramPacket(buf, buf.length, InetAddress.getByName(ip), port);
    }
}
