package leasegate.gitgate.core;

/// The name and email a commit carries.
public record Identity(String name, String email) {

    @Override public String toString() { return name + " <" + email + ">"; }
}
