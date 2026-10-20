package leasegate.gitgate.core;

import datapotter.datahelper.Data;

/// Defaults for `git1st`, which makes the current folder a repository and its GitHub home.
@Data
public final class NewRepo extends NewRepo_A {
    String defaultAccount;
    String gitignore = "java";
    String gitignoreTemplates;
}
