// SPDX-License-Identifier: MIT
contract ArrayContents {
    // a[i] = t (exact); a[j] = t / d (rounded); with j == i the read returns the rounded value.
    function overwrite(uint256[] memory a, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        a[i] = t;
        a[j] = t / d;
        return a[i];
    }
    // the same through a rounded index: with k rounded, the runs overwrite different cells.
    function overwriteRoundedIndex(uint256[] memory a, uint256 t, uint256 d, uint256 i) external returns (uint256) {
        a[i] = t;
        uint256 k = (t / d) * d;
        last = k;
        a[k] = 0;
        return a[i];
    }
    uint256 last;

    struct P { uint256[] amounts; }
    // through a struct field: each p.amounts is a separate read of the field
    function viaStruct(P memory p, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        p.amounts[i] = t / d;
        return p.amounts[j];
    }
    // through a merge: b is a or a2
    function viaMerge(uint256[] memory a, uint256[] memory a2, bool c, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        uint256[] memory b = c ? a : a2;
        a[i] = t / d;
        return b[j];
    }
    // nested: m[0] is read twice
    function viaNested(uint256[][] memory m, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        m[0][i] = t / d;
        return m[0][j];
    }
    // a local copy is the same SSA value
    function viaCopy(uint256[] memory a, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        uint256[] memory b = a;
        b[i] = t / d;
        return a[j];
    }

    // read before the only store: exact
    function readThenWrite(uint256[] memory a, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256 r) {
        r = a[j];
        a[i] = t / d;
    }
    // in a loop the store of one iteration precedes the read of the next: Down
    function readThenWriteLoop(uint256[] memory a, uint256 t, uint256 d, uint256 i, uint256 j, uint256 n) external pure returns (uint256 r) {
        for (uint256 k = 0; k < n; k++) {
            r += a[j];
            a[i] = t / d;
        }
    }
}
