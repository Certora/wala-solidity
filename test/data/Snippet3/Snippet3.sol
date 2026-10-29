// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract Snippet3 {

    // totalPayment counts up to x = y / z, so it equals the rounded-down quotient
    function pay(uint256 y, uint256 z) public pure returns (uint256) {
        uint256 x = y / z;
        uint256 i = 0;
        uint256 totalPayment = 0;
        while (i < x) {
            totalPayment++;
            i++;
        }
        return totalPayment;
    }
}
