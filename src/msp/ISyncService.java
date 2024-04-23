package msp;

import arc.scene.style.Drawable;
import mindustry.gen.Icon;

public interface ISyncService {
    String getName();
    default Drawable getIcon() { return null; }

    void settings();
}