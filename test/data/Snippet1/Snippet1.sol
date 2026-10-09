// SPDX-License-Identifier: MIT
// pragma solidity ^0.8;

contract Snippet1 {

    // x = y / z, then if x <= minPayment, bump it to minPayment + 
    function pay(uint256 y, uint256 z, uint256 minPayment) public pure returns (uint256) {
        uint256 x = y / z;
        if (x <= minPayment) {
            x = minPayment + 1;
        }
        return x;
    }
}
