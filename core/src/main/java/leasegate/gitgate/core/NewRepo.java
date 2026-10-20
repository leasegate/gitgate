package leasegate.gitgate.core;

import datapotter.datahelper.Data;

/// `[newRepo]`: the account used where nothing else names one -- reading from a GitHub
/// organisation that no account's `orgs` lists.
@Data
public final class NewRepo extends NewRepo_A {
    String defaultAccount;
}
