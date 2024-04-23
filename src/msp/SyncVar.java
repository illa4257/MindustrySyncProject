package msp;

public class SyncVar<T> {
    public final Object locker = new Object();

    private T value;

    public SyncVar() { value = null; }
    public SyncVar(T value) { this.value = value; }

    public T get() {
        synchronized (locker) {
            return value;
        }
    }

    public SyncVar<T> set(final T newValue) {
        synchronized (locker) {
            value = newValue;
        }
        return this;
    }
}