// SPDX-License-Identifier: MIT
contract EarlyReturn {
    // Snippet1 with an early return: the integer run can take the then-branch where the real run
    // does not, and m + 1 exceeds the real quotient there, so the two runs disagree.
    function bump(uint256 y, uint256 z, uint256 m) public pure returns (uint256) {
        uint256 x = y / z;
        if (x <= m) {
            return m + 1;
        }
        return x;
    }

    // Snippet2 with an early return: the clamped value never exceeds the real quotient.
    function clamp(uint256 y, uint256 z, uint256 m) public pure returns (uint256) {
        uint256 x = y / z;
        if (x <= m) {
            return m;
        }
        return x;
    }
}
