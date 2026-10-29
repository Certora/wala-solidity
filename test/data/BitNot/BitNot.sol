// SPDX-License-Identifier: MIT
contract BitNot {
    // ~x is -x - 1: complementing a rounded-down quotient rounds up.
    function solNot(uint256 a, uint256 b) public pure returns (uint256) {
        return ~(a / b);
    }

    // Yul not is the same bitwise complement (it used to lower as logical negation).
    function yulNot(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := not(div(a, b)) }
    }

    // Unary minus on a signed rounded quotient flips the direction.
    function negQuotient(int256 a, int256 b) public pure returns (int256) {
        return -(a / b);
    }
}
