package msp;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

import java.util.ArrayList;

public class ABaseDialog extends BaseDialog {
    private final ArrayList<Runnable> onClose = new ArrayList<>();

    public ABaseDialog(String title, DialogStyle style) { super(title, style); }
    public ABaseDialog(String title) { super(title); }

    @Override
    public void addCloseButton(float width){
        buttons.defaults().size(width, 64f);
        buttons.button("@back", Icon.left, () -> {
            hide();
            for (final Runnable r : onClose)
                try {
                    r.run();
                } catch (final Throwable ex) {
                    Log.err(ex);
                }
        }).size(width, 64f);

        closeOnBack(() -> Core.app.post(() -> {
            for (final Runnable r : onClose)
                try {
                    r.run();
                } catch (final Throwable ex) {
                    Log.err(ex);
                }
        }));
    }

    public void onClose(final Runnable runnable) {
        if (runnable == null)
            return;
        onClose.add(runnable);
    }

    public void offClose(final Runnable runnable) {
        if (runnable == null)
            return;
        onClose.remove(runnable);
    }

    public void removeAllCloseListeners() {
        onClose.clear();
    }
}
