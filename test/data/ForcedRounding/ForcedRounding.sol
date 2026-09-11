// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract ForcedRounding {

    // Dividing by 1 is exact
    function forcedDown(uint256 x) public pure returns (uint256) {
        return x / 1;
    }

    // Dividing by 1 then adding 0 is still exact: the 0 is added in both runs
    function forcedUp(uint256 x) public pure returns (uint256) {
        uint256 y = x / 1;
        y = y + 0;
        return y;
    }

    // Dividing by 3 rounds down
    function roundedDown(uint256 x) public pure returns (uint256) {
        return x / 3;
    }
}
