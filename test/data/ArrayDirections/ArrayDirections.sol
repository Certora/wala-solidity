// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract ArrayDirections {
    uint256[] arr;
    mapping(uint256 => uint256) m;
    uint256 last;

    // Write through a rounded index. k is stored, so it is an amount, and it is Down:
    // with t = 5, d = 2 the integer run writes element 4 and the real run element 5,
    // so arr[4] differs between the runs. Expect Inconsistent.
    function writeRoundedIndex(uint256 t, uint256 d) external returns (uint256) {
        uint256 k = (t / d) * d;
        last = k;
        arr[k] = 7;
        return arr[4];
    }

    // A rounded value written at i and read back through another index j.
    // When j == i the read is rounded down; otherwise it is an untouched (exact) element.
    // Expect Down.
    function launderedRead(uint256 t, uint256 d, uint256 i, uint256 j) external returns (uint256) {
        arr[i] = t / d;
        return arr[j];
    }

    // The same two cases through a mapping.
    function writeRoundedKey(uint256 t, uint256 d) external returns (uint256) {
        uint256 k = (t / d) * d;
        last = k;
        m[k] = 7;
        return m[4];
    }

    function launderedMapping(uint256 t, uint256 d, uint256 i, uint256 j) external returns (uint256) {
        m[i] = t / d;
        return m[j];
    }

    // Control: aliased read through the same index keeps the written value's direction. Expect Down.
    function aliasedRead(uint256 t, uint256 d, uint256 i) external returns (uint256) {
        arr[i] = t / d;
        return arr[i];
    }

    // Memory arrays: the path the array rule in the code covers.

    // Write through a rounded index (k stored, so an amount, and Down). Expect Inconsistent.
    function memWriteRoundedIndex(uint256[] memory a, uint256 t, uint256 d) external returns (uint256) {
        uint256 k = (t / d) * d;
        last = k;
        a[k] = 7;
        return a[4];
    }

    // Rounded value written at i, read through j. Expect Down.
    function memLaunderedRead(uint256[] memory a, uint256 t, uint256 d, uint256 i, uint256 j) external pure returns (uint256) {
        a[i] = t / d;
        return a[j];
    }

    // Control: aliased read through the same index. Expect Down.
    function memAliasedRead(uint256[] memory a, uint256 t, uint256 d, uint256 i) external pure returns (uint256) {
        a[i] = t / d;
        return a[i];
    }

    // Control: untouched memory array read through an exact index. Expect Neither.
    function memPlainRead(uint256[] memory a, uint256 i) external pure returns (uint256) {
        return a[i];
    }

    // Diagnostics for the return direction.
    function divReturn(uint256 t, uint256 d) external pure returns (uint256) {
        return t / d;
    }

    function memAliasedReadPlus(uint256[] memory a, uint256 t, uint256 d, uint256 i) external pure returns (uint256) {
        a[i] = t / d;
        return a[i] + 1;
    }

    function memAliasedReadInternal(uint256[] memory a, uint256 t, uint256 d, uint256 i) public pure returns (uint256) {
        return helper(a, t, d, i);
    }

    function helper(uint256[] memory a, uint256 t, uint256 d, uint256 i) internal pure returns (uint256) {
        a[i] = t / d;
        uint256 r = a[i];
        return r;
    }
}
