package msp.base;

import arc.Core;
import arc.files.Fi;
import arc.scene.style.Drawable;
import arc.scene.ui.Label;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import msp.ISyncData;
import msp.ISyncContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class SchematicsData implements ISyncData {
    @Override public String getName() { return "@schematics"; }
    @Override public Drawable getIcon() { return Icon.paste; }
    @Override public void settings() {}

    @Override
    public void sync(final ISyncContext c) throws IOException {
        if (c.isStatic())
            return;
        final BaseDialog d = c.getDialog();

        final Fi schemeDir = Core.settings.getDataDirectory().child("schematics/");

        final Label schematicsStatus = new Label(Core.bundle.get("schematics") + ": 0 / ??? | ??? / ??? (???)");
        Core.app.post(() -> d.cont.add(schematicsStatus).row());

        final Fi[] schematicsList = schemeDir.list();
        int t = 0;
        for (final Fi fi : schematicsList)
            if (!fi.isDirectory())
                t++;

        c.writeInt(t);
        for (final Fi fi : schematicsList)
            if (!fi.isDirectory())
                c.writeString(fi.name());
        c.flush();

        final int totalSchematics = c.readInt();
        Core.app.post(() -> schematicsStatus.setText(Core.bundle.get("schematics") + ": 0 / ??? | ??? / ??? (" + totalSchematics + ")"));

        final ArrayList<String> names = new ArrayList<>();
        sm:
        for (int i = 0; i < totalSchematics; i++) {
            final String n = c.readString();
            if (n.contains("/") || n.contains("\\"))
                continue;
            for (final Fi f : schematicsList)
                if (f.name().equals(n) || !n.endsWith(".msch")) {
                    if (f.length() == 0) {
                        f.delete();
                        break;
                    }
                    continue sm;
                }
            names.add(n);
        }

        // Sync

        final int syncTotalSchematics = names.size();
        Core.app.post(() -> schematicsStatus.setText(Core.bundle.get("schematics") + ": 0 / ??? | 0 / " + syncTotalSchematics + " (" + totalSchematics + ") - Preparing ..."));

        c.writeInt(names.size());
        c.flush();

        for (final String n : names) {
            c.write(4);
            c.writeString(n);
            c.flush();
        }

        final int totalRemoteSchematics = c.readInt();
        byte b;
        int fileIndex = 0, fileIndex2 = 0;
        long nextUpdate = 0, current;
        smn:
        while (true) {
            current = System.currentTimeMillis();
            if (current >= nextUpdate) {
                nextUpdate = current + 100;
                final int fi = fileIndex, fi2 = fileIndex2;
                Core.app.post(() -> schematicsStatus.setText(Core.bundle.get("schematics") + ": " + fi2 + " / " + totalRemoteSchematics + " | " + fi + " / " + syncTotalSchematics + " (" + totalSchematics + ")"));
            }

            if (fileIndex2 >= totalRemoteSchematics && fileIndex >= syncTotalSchematics)
                break;

            b = c.readByte();

            if (b == 4) {
                final String n = c.readString();
                for (final Fi f : schematicsList)
                    if (f.name().equals(n)) {
                        if (!f.isDirectory() && n.endsWith(".msch")) {
                            c.write(3);
                            c.sendFile(f);
                            continue smn;
                        } else
                            break;
                    }
                c.write(2);
                c.flush();
                continue;
            }

            if (b == 3) {
                if (names.size() > fileIndex) {
                    final Fi f = schemeDir.child(names.get(fileIndex));
                    try (final ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                        c.readFile(o);
                        final Object l = new Object();
                        synchronized (l) {
                            Core.app.post(() -> {
                                try {
                                    final Schematic s = Schematics.read(new ByteArrayInputStream(o.toByteArray()));
                                    s.file = f;
                                    if (s.hasSteamID())
                                        s.removeSteamID();
                                    if (f.exists())
                                        f.delete();
                                    Vars.schematics.add(s);
                                } catch (final Throwable e) {
                                    d.cont.add(Core.bundle.format("sync-file-error", f.name(), e.getMessage())).row();
                                    Log.err("File: " + f.name());
                                    Log.err(e);
                                    try {
                                        f.delete();
                                    } catch (final Exception ex) {
                                        Log.err(ex);
                                    }
                                }
                                synchronized (l) {
                                    l.notifyAll();
                                }
                            });
                            l.wait();
                        }
                    } catch (final InterruptedException ex) {
                        Log.err(ex);
                    }
                } else
                    c.readFile(null);
                fileIndex++;
                c.write(1);
                c.flush();
                continue;
            }

            if (b == 2) {
                fileIndex++;
                continue;
            }

            if (b == 1) {
                fileIndex2++;
                continue;
            }

            throw new IOException("Unknown packet code: " + b);
        }

        final int fi = fileIndex, fi2 = fileIndex2;
        Core.app.post(() -> schematicsStatus.setText(Core.bundle.get("schematics") + ": " + fi2 + " / " + totalRemoteSchematics + " | " + fi + " / " + syncTotalSchematics + " (" + totalSchematics + ")"));
    }
}