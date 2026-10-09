// SPDX-License-Identifier: MIT
contract BiasDivision {
    // (A + D - 1) / D is ceil(A / D): Up, however the bias is spelled.
    function biasLeft(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + b - 1) / b;
    }

    function biasGrouped(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + (b - 1)) / b;
    }

    function biasFirst(uint256 a, uint256 b) public pure returns (uint256) {
        return (a - 1 + b) / b;
    }

    function biasProduct(uint256 x, uint256 y, uint256 b) public pure returns (uint256) {
        return (x * y + b - 1) / b;
    }

    function biasSum(uint256 x, uint256 y, uint256 b) public pure returns (uint256) {
        return (x + y + b - 1) / b;
    }

    function biasCompoundDivisor(uint256 a, uint256 b, uint256 c) public pure returns (uint256) {
        return (a + b + c - 1) / (b + c);
    }

    // The divisor shared with a dividend addend is not enough: these all round Down.
    function sharedOnly(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + b) / b;
    }

    function biasTooSmall(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + b - 2) / b;
    }

    function sharedScaled(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + 2 * b - 1) / b;
    }
}
