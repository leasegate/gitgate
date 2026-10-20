package leasegate.gitgate.core;

import datapotter.datahelper.Data;

/// The identity a repository's commits carry when it is marked anonymous: deliberately meaningless.
/// The account still decides whose key pushes it.
@Data
public final class Anonymous extends Anonymous_A {
    String userName = "User";
    String userEmail = "user@example.com";
}
