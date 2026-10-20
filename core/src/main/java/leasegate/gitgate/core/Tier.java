package leasegate.gitgate.core;

/// How much a request can do with an account. A grant for one tier covers the tiers below it.
/// ADMIN is what cannot be undone or reaches further than one repository's content: deleting a
/// repository, secrets, keys, the token itself.
public enum Tier {
    READ("read"), WRITE("write"), ADMIN("admin");

    public final String word;

    Tier(String word) { this.word = word; }

    public static Tier of(String s) {
        for (var t : values()) if (t.word.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) return t;
        throw new IllegalArgumentException("not a tier: " + s);
    }
}
