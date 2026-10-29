// SPDX-License-Identifier: MIT
contract Shifts {
    // Yul shr takes the shift amount first: shr(96, v) is v >> 96.
    function yulShiftProduct(uint256 x, uint256 y) public pure returns (uint256 r) {
        assembly { r := shr(96, mul(x, y)) }
    }

    // The shifted value is itself a rounded quotient: the whole thing still rounds down.
    // With the operands reversed this reads as 1 / 2^(a/b) and comes out Inconsistent.
    function yulShiftQuotient(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := shr(1, div(a, b)) }
    }

    // The Solidity-level shift, for contrast: operands arrive in source order.
    function solShift(uint256 a, uint256 b) public pure returns (uint256) {
        return (a / b) >> 1;
    }
}
