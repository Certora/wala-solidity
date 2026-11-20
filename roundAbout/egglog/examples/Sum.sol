// SPDX-License-Identifier: MIT
contract Sum {
    // (x + d - 1 + y) / d : round-up of (x + y) / d; the code never computes x + y
    function sumExact(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return (x + d - 1 + y) / d;
    }
    // the same with x replaced by a quotient that rounds down
    function sumDown(uint256 x, uint256 y, uint256 k, uint256 d) public pure returns (uint256) {
        return (x / k + d - 1 + y) / d;
    }
    // one addend is a product
    function sumProduct(uint256 a, uint256 b, uint256 y, uint256 d) public pure returns (uint256) {
        return (a * b + d - 1 + y) / d;
    }
    // the dividend is read again after the division
    function sumReused(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        uint256 t = x + d - 1 + y;
        return t / d + t;
    }
    // three addends: x + y + z is not the sum of two values the code computes
    function sumThree(uint256 x, uint256 y, uint256 z, uint256 d) public pure returns (uint256) {
        return (x + d - 1 + y + z) / d;
    }
    // control: the plain bias
    function plain(uint256 n, uint256 d) public pure returns (uint256) {
        return (n + d - 1) / d;
    }
}
