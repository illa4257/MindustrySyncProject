package msp;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.Label;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.*;
import msp.base.LocalSyncService;
import msp.base.SchematicsData;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Pattern;

public class MindustrySyncProject extends Mod {
    public static final Pattern DEVICE_NAME_FILTER = Pattern.compile("[\r\n\t/\\\\]");
    public static final int DEVICE_NAME_MAX_LENGTH = 16;

    public final SyncVar<byte[]> deviceId = new SyncVar<>();
    public final SyncVar<String> deviceName = new SyncVar<>();
    public final ArrayList<ISyncService> syncServices = new ArrayList<>();
    public final HashMap<String, ISyncData> syncDataList = new HashMap<>();

    public MindustrySyncProject() {
        if (Vars.headless) {
            Log.info("[MSP] Servers are not supported ...");
            return;
        }

        Log.info("[MSP] Loaded MSP constructor.");

        {
            boolean save = false;

            try {
                final Fi f = getConfig();
                if (f.exists()) {
                    final Jval v = Jval.read(f.readString());

                    if (v.has("uuid")) {
                        try {
                            final UUID uuid = UUID.fromString(v.getString("uuid"));
                            final ByteBuffer b = ByteBuffer.wrap(new byte[16]);
                            b.putLong(uuid.getMostSignificantBits());
                            b.putLong(uuid.getLeastSignificantBits());
                            deviceId.set(b.array());
                        } catch (final IllegalFormatException ex) {
                            Log.err(ex);
                            Events.on(EventType.ClientLoadEvent.class, e -> Vars.ui.showException(ex));
                        }
                    }

                    if (v.has("deviceName"))
                        deviceName.set(DEVICE_NAME_FILTER.matcher(v.getString("deviceName")).replaceAll(""));
                }
            } catch (final Exception ex) {
                Log.err(ex);
                Events.on(EventType.ClientLoadEvent.class, e -> Vars.ui.showException(ex));
            }

            if (deviceId.get() == null) {
                deviceId.set(new byte[16]);
                new Random().nextBytes(deviceId.get());
                save = true;
            }

            if (deviceName.get() == null || deviceName.get().isEmpty() || deviceName.get().length() > 16) {
                deviceName.set("device-" + randomString(8, (Core.bundle.getLocale().getLanguage().equals("uk_UA") ? "Ї" : "") + "ABCDEFGHIJKLMNOPQRSTUVWXY1234567890"));
                save = true;
            }

            if (save)
                save();
        }

        //Drawable i = (Drawable) Core.atlas.find("msp-frog");

        //listen for game load event
        /*Events.on(ClientLoadEvent.class, e -> {
            //show dialog upon startup
            Time.runTask(10f, () -> {
                BaseDialog dialog = new BaseDialog("frog");
                dialog.cont.add("behold").row();
                //mod sprites are prefixed with the mod name (this mod is called 'example-java-mod' in its config)
                dialog.cont.image(Core.atlas.find("mindustry-sync-project-frog")).pad(20f).row();
                dialog.cont.button("I see", dialog::hide).size(100f, 50f);
                dialog.show();
            });
        });*/

        syncServices.add(new LocalSyncService(this));
        syncDataList.put(SchematicsData.class.getName(), new SchematicsData());

        Events.on(EventType.ClientLoadEvent.class, e -> {
            // new TextureRegionDrawable(Core.atlas.find("mindustry-sync-project-frog")
            Vars.ui.settings.addCategory("@sync-menu", Icon.refresh, t -> {
                t.defaults().size(280f, 60f);
                t.button("@category.general", Icon.settings, () -> {
                    final ABaseDialog d = new ABaseDialog("@category.general");
                    d.onClose(this::save);
                    d.addCloseButton();
                    d.cont.defaults().size(280f, 60f);

                    d.cont.add("@sync-device-name").row();

                    final Label err = new Label("");
                    d.cont.field(deviceName.get(), s -> {
                        if (s.isEmpty()) {
                            err.setText("@sync-empty-field");
                            return;
                        }

                        if (s.length() > DEVICE_NAME_MAX_LENGTH) {
                            err.setText(Core.bundle.format("sync-so-long-field", DEVICE_NAME_MAX_LENGTH));
                            return;
                        }

                        if (DEVICE_NAME_FILTER.matcher(s).find()) {
                            err.setText(
                                    Core.bundle.format("sync-filter-problem", DEVICE_NAME_FILTER.pattern()
                                            .replaceAll("\n", "\\\\n")
                                            .replaceAll("\r", "\\\\r")
                                            .replaceAll("\t", "\\\\t")
                                    )
                            );
                            return;
                        }

                        err.setText("");

                        deviceName.set(s);
                    }).row();

                    err.setColor(Color.red);
                    d.cont.add(err).row();

                    d.show();
                }).row();
                t.add("@sync-services").row();
                for (final ISyncService s : syncServices) {
                    final Drawable i = s.getIcon();
                    if (i == null)
                        t.button(s.getName(), s::settings).row();
                    else
                        t.button(s.getName(), i, s::settings).row();
                }
                t.add("@sync-data").row();
                for (final ISyncData s : syncDataList.values()) {
                    final Drawable i = s.getIcon();
                    if (i == null)
                        t.button(s.getName(), s::settings).row();
                    else
                        t.button(s.getName(), i, s::settings).row();
                }
            });

            /*final BaseDialog d = new BaseDialog("");
            d.cont.add("Syncing ...").row();
            for (final Fi f : Core.settings.getDataDirectory().child("saves/").list())
                d.cont.add(f.name()).row();
            for (final Fi f : Core.settings.getDataDirectory().child("schematics/").list())
                d.cont.add(f.name()).row();
            d.show();
            Time.runTask(10f, d::hide);*/
        });
    }

    public void save() {
        final Fi f = getConfig();
        try (final OutputStreamWriter o = new OutputStreamWriter(f.write())) {
            final Jval m = Jval.newObject();
            m.put("uuid", Jval.valueOf(UUID.nameUUIDFromBytes(deviceId.get()).toString()));
            m.put("deviceName", Jval.valueOf(deviceName.get()));
            m.writeTo(o);
        } catch (final IOException ex) {
            Log.err(ex);
            Core.app.post(() -> Vars.ui.showException(ex));
        }
    }

    private static String randomString(int length, final String chars) {
        final int t = chars.length();
        final Random r = new Random();
        final StringBuilder b = new StringBuilder();
        for (; length > 0; length--)
            b.append(chars.charAt(r.nextInt(t)));
        return b.toString();
    }
}
