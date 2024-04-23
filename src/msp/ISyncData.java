package msp;

import arc.scene.style.Drawable;

import java.io.IOException;

public interface ISyncData {
    String getName();
    default Drawable getIcon() { return null; }
    void settings();
    void sync(final ISyncContext context) throws IOException;
}