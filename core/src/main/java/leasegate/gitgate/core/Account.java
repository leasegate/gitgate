package leasegate.gitgate.core;

import java.util.List;

import datapotter.datahelper.Data;

/// One GitHub identity: who commits, which folders and organisations are its own, and how its SSH
/// key is reached -- `gate` (the gitgate tray's agent; the key never leaves it) or the path of a key
/// file (the old way, readable by every program; kept only to migrate from). `forbidden` holds
/// regular expressions that must never appear in anything pushed under this account. `alsoEmails`
/// are further addresses this account's own commits may carry (a GitHub noreply address).
@Data
public final class Account extends Account_A {
    String id;
    String name;
    String description;
    String userName;
    String userEmail;
    List<String> alsoEmails = List.of();
    List<String> orgs = List.of();
    List<String> paths = List.of();
    List<String> pathRegex = List.of();
    String ssh = "gate";
    List<String> forbidden = List.of();

    public boolean viaGate() { return ssh == null || ssh.isBlank() || ssh.equalsIgnoreCase("gate"); }

    /// Every address this account's own commits may carry.
    public java.util.Set<String> ownEmails() {
        var out = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (userEmail != null) out.add(userEmail);
        out.addAll(alsoEmails);
        return out;
    }
}
