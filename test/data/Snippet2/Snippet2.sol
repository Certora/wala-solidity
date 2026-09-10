// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract Snippet2 {

    // x = y / z, then if x <= minPayment, clamp it to minPayment
    function pay(uint256 y, uint256 z, uint256 minPayment) public pure returns (uint256) {
        uint256 x = y / z;
        if (x <= minPayment) {
            x = minPayment;
        }
        return x;
    }
}
