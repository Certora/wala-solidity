// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity ^0.8.0;

contract ProductCeiling {
    uint256 internal constant ONE = 1e18;

    // original Balancer shape: overflow require + (x-1)/ONE + 1
    function mulUp(uint256 a, uint256 b) public pure returns (uint256) {
        uint256 product = a * b;
        require(a == 0 || product / a == b);
        if (product == 0) { return 0; }
        else { return ((product - 1) / ONE) + 1; }
    }

    // same but 1 + (...) like divUp writes it
    function mulUpFlipped(uint256 a, uint256 b) public pure returns (uint256) {
        uint256 product = a * b;
        require(a == 0 || product / a == b);
        if (product == 0) { return 0; }
        else { return 1 + ((product - 1) / ONE); }
    }

    // no overflow require
    function mulUpNoCheck(uint256 a, uint256 b) public pure returns (uint256) {
        uint256 product = a * b;
        if (product == 0) { return 0; }
        else { return ((product - 1) / ONE) + 1; }
    }

    // divisor is a parameter instead of the ONE constant
    function mulUpParamDiv(uint256 a, uint256 b, uint256 d) public pure returns (uint256) {
        uint256 product = a * b;
        if (product == 0) { return 0; }
        else { return ((product - 1) / d) + 1; }
    }

    // dividend is a parameter (divUp shape, trailing +1)
    function divUpTrailing(uint256 a, uint256 b) public pure returns (uint256) {
        require(b != 0);
        if (a == 0) { return 0; }
        else { return (a - 1) / b + 1; }
    }

    // reference divUp exactly as Balancer writes it
    function divUp(uint256 a, uint256 b) public pure returns (uint256) {
        require(b != 0);
        if (a == 0) { return 0; }
        else { return 1 + (a - 1) / b; }
    }
}
