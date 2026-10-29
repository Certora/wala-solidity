// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract PositionDivergence {

    // Binary search for an exact key: low/high are positions assigned under an exact guard,
    // so both runs read the same element. Expect the read value's direction (Exact).
    function lookupExact(uint256[] calldata keys, uint256[] calldata vals, uint256 key)
        external pure returns (uint256) {
        uint256 low = 0;
        uint256 high = keys.length;
        while (low < high) {
            uint256 mid = (low + high) / 2;
            if (keys[mid] < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return vals[low];
    }

    // Same search for a rounded key: the guard keys[mid] < key is rounded, so the two runs can
    // move low differently and read different elements. Expect Inconsistent.
    function lookupRounded(uint256[] calldata keys, uint256[] calldata vals, uint256 t, uint256 d)
        external pure returns (uint256) {
        uint256 key = t / d;
        uint256 low = 0;
        uint256 high = keys.length;
        while (low < high) {
            uint256 mid = (low + high) / 2;
            if (keys[mid] < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return vals[low];
    }
}
