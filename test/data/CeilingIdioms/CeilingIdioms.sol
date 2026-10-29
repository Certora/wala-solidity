// SPDX-License-Identifier: MIT
contract CeilingIdioms {
    function toUint(bool b) internal pure returns (uint256 u) {
        assembly { u := iszero(iszero(b)) }
    }

    // ceil(x / d) as floor + [remainder > 0], Yul comparison
    function yulGt(uint256 x, uint256 d) public pure returns (uint256 z) {
        assembly { z := add(div(x, d), gt(mod(x, d), 0)) }
    }

    // ceil(x / d) as [remainder != 0] + floor, Yul double iszero
    function yulIsZero(uint256 x, uint256 d) public pure returns (uint256 z) {
        assembly { z := add(iszero(iszero(mod(x, d))), div(x, d)) }
    }

    // ceil(x * y / d) with the indicator from a call and the mulmod builtin
    function mulDivUp(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return x * y / d + toUint(mulmod(x, y, d) > 0);
    }

    // ceil(a / b) as [a != 0] * ((a - 1) / b + 1)
    function ceilDiv(uint256 a, uint256 b) public pure returns (uint256) {
        return toUint(a > 0) * ((a - 1) / b + 1);
    }

    // ceil(a / b) as (a - 1) / b + 1 behind an a != 0 guard
    function divUp(uint256 a, uint256 b) public pure returns (uint256) {
        if (a == 0) {
            return 0;
        }
        return (a - 1) / b + 1;
    }

    // not ceilings: adds 1 when the remainder is zero
    function wrongPolarity(uint256 x, uint256 d) public pure returns (uint256) {
        return x / d + toUint(x % d == 0);
    }

    // not ceilings: nothing establishes a != 0
    function unguardedPredecrement(uint256 a, uint256 b) public pure returns (uint256) {
        return (a - 1) / b + 1;
    }

    // not ceilings: a constant added to a quotient
    function plusConstant(uint256 x) public pure returns (uint256) {
        return x / 3 + 5;
    }
}
