#!/usr/bin/env python3
"""The corpus manifest: the single source of truth for the 21 protocol configurations.

Every batch runner and checker iterates THIS list and fails loudly on any mismatch;
nothing discovers fixtures from the filesystem. Paths are relative to the private
suite environment's root (the roundabout-tests overlay).
"""

# (extract test prefix, fixture dir, conf, ast)
CORPUS = [
    ("TestAaveV3PoolInstanceSanity", "test/data/AaveV3/PoolInstance",
     "PoolInstance_sanity.conf", "ast/.asts.json.bz2"),
    ("TestAaveV3PoolInstanceBuiltin", "test/data/AaveV3/PoolInstance",
     "PoolInstance_builtin_assertions.conf", "ast/.asts.json.bz2"),
    ("TestAaveV4HubValidState", "test/data/AaveV4/HubValidState",
     "HubValidState.conf", "ast/.asts.json.bz2"),
    ("TestAaveV4Liquidation", "test/data/AaveV4/Liquidation",
     "Liquidation.conf", "ast/.asts.json.bz2"),
    ("TestBalancerStablePool4f189ea1", "test/data/BalancerV2/stablePool/4f189ea1",
     "stablePool.conf", "ast/.asts.json.bz2"),
    ("TestBalancerStablePoolPaminaNov25", "test/data/BalancerV2/stablePool/PaminaNov25",
     "stablePool.conf", "ast/.asts.json.bz2"),
    ("TestCorkAuxiliary", "test/data/Cork/0Auxiliary",
     "0-auxiliary.conf", "ast/.asts.json.bz2"),
    ("TestCozyEuler", "test/data/CozyEuler/TrancheRaiseStrategy",
     "EulerTrancheRaiseStrategy.conf", "ast/.asts.json.bz2"),
    ("TestEigenLayer", "test/data/EigenLayer/EigenPodManagerRules",
     "EigenPodManagerRules.conf", "ast/.asts.json.bz2"),
    ("TestEnsEthRegistrar", "test/data/ENS/ETHRegistrar",
     "ETHRegistrar_commitment_rules.conf", "ast/.asts.json.bz2"),
    ("TestEulerEarn", "test/data/EulerEarn/Solvency",
     "Solvency.conf", "ast/.asts.json.bz2"),
    ("TestGhoGsmOptimality", "test/data/Gho/GsmOptimality",
     "optimality.conf", "ast/.asts.json.bz2"),
    ("TestInfiniFiMintController", "test/data/InfiniFi/MintController",
     "MintController.conf", "ast/.asts.json.bz2"),
    ("TestMezzanine", "test/data/Mezzanine/IssuanceVault",
     "IssuanceVault.conf", "ast/.asts.json.bz2"),
    ("TestMorphoMidnight", "test/data/MorphoV2/Midnight",
     "Midnight.conf", "ast/.asts.json.bz2"),
    ("TestMorphoSharePrice", "test/data/MorphoV2/SharePrice",
     "SharePrice.conf", "ast/.asts.json.bz2"),
    ("TestRoycoAccountant", "test/data/RoycoDawn/AccoutantSanity",
     "base-RoycoAccountant_sanity.conf", "ast/.asts.json.bz2"),
    ("TestSaturnDollar", "test/data/SaturnDollar/USDatBacking",
     "USDatBacking.conf", "ast/.asts.json.bz2"),
    ("TestTokemakVault", "test/data/Tokemak/LMPVault",
     "LMPVault.conf", "ast/.asts.json.bz2"),
    ("TestTokemakStrategy", "test/data/Tokemak/LMPStrategy",
     "LMPStrategy.conf", "ast/.asts.json.bz2"),
    ("TestVedaBoring", "test/data/VedaBoring/AccountantWithRateProviders",
     "accountantWithRateProviders.conf", "ast/.asts.json.bz2"),
]

PROT = {t for t, _, _, _ in CORPUS}
assert len(CORPUS) == 21, f"corpus manifest must list 21 configurations, has {len(CORPUS)}"
assert len(PROT) == 21, "duplicate test prefixes in the manifest"


def tag(test):
    """The results-file tag for one configuration."""
    return test


if __name__ == '__main__':
    import os
    import sys
    # Verify the manifest against a suite environment root: every dir/conf/ast exists.
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    bad = []
    for t, d, conf, ast in CORPUS:
        for rel in (os.path.join(d, conf), os.path.join(d, ast)):
            p = os.path.join(root, rel)
            if not (os.path.isfile(p) or os.path.isfile(p.removesuffix('.bz2'))):
                bad.append((t, rel))
    if bad:
        for t, rel in bad:
            print(f"MISSING {t}: {rel}")
        raise SystemExit(1)
    print(f"manifest OK: all {len(CORPUS)} configurations present under {root}")
