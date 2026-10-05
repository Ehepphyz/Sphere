package com.sphere.core.hepmc3;

/**
 * Base of every attribute an event, a particle, a vertex or a run can carry.
 *
 * <p>Read from a file, an attribute is a string that nobody has parsed yet
 * (a {@link StringAttribute} built "unparsed"); asking the event for it as a
 * given type parses it then, once, and keeps the typed object in its place.
 * {@link #fromString} parses, {@link #serialize} writes it back; the writers
 * store exactly what serialize returns.
 */
public abstract class Attribute {

    private boolean parsed;
    private String string = "";
    GenEvent event;
    GenParticle particle;
    GenVertex vertex;

    /** A parsed attribute. */
    protected Attribute() {
        this.parsed = true;
    }

    /** An attribute holding a string not yet parsed, as read from a file. */
    protected Attribute(String unparsed) {
        this.parsed = false;
        this.string = unparsed == null ? "" : unparsed;
    }

    /** Fills the attribute from its string form; false when it cannot. */
    public abstract boolean fromString(String att);

    /** Called after fromString when the attribute belongs to an event. */
    public boolean init() {
        return true;
    }

    /** Called after fromString when the attribute belongs to a run. */
    public boolean init(GenRunInfo run) {
        return true;
    }

    /** The string form, or null when the attribute cannot be serialised (C++: to_string returning false). */
    public abstract String serialize();

    public boolean isParsed() {
        return parsed;
    }

    public String unparsedString() {
        return string;
    }

    /** The event that owns it, or null. */
    public GenEvent event() {
        return event;
    }

    /** The particle it is attached to, when it is a particle's. */
    public GenParticle particle() {
        return particle;
    }

    /** The vertex it is attached to, when it is a vertex's. */
    public GenVertex vertex() {
        return vertex;
    }

    protected void setIsParsed(boolean flag) {
        parsed = flag;
    }

    protected void setUnparsedString(String st) {
        string = st == null ? "" : st;
    }

    /** The serialised form, or a mark when there is none. */
    @Override
    public String toString() {
        final String s = serialize();
        return s == null ? "<not serialisable " + getClass().getSimpleName() + ">" : s;
    }

    /* ---- typed construction, as std::make_shared<T>() ---------------------- */

    private static final ClassValue<java.lang.reflect.Constructor<?>> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Constructor<?> computeValue(Class<?> type) {
            try {
                final java.lang.reflect.Constructor<?> c = type.getDeclaredConstructor();
                c.setAccessible(true);
                return c;
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(type.getName() + " has no constructor without arguments", e);
            }
        }
    };

    /** A new, empty attribute of the type asked for. */
    static <T extends Attribute> T make(Class<T> type) {
        try {
            return type.cast(CONSTRUCTORS.get(type).newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create a " + type.getName(), e);
        }
    }
}
