// SPDX-License-Identifier: MIT
contract RoundedIndex {
    // The index also reaches an amount, so it keeps its rounding (Down), and the
    // two runs can read different cells: the loaded value is Inconsistent.
    function pick(uint256[] calldata tiers, uint256 total, uint256 count, uint256 fee)
        external pure returns (uint256)
    {
        uint256 x = total / count;
        uint256 r = tiers[x];
        return r + x + fee;
    }

    // A pure index: it only names a cell, both runs read the same one, and the
    // loaded value stays an ordinary unknown.
    function probe(uint256[] calldata tiers, uint256 lo, uint256 hi)
        external pure returns (uint256)
    {
        uint256 mid = (lo + hi) / 2;
        return tiers[mid];
    }
}
