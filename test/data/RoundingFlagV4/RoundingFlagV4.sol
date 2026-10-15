// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// OpenZeppelin's mulDiv with a rounding mode, in its v5 and v4.9 shapes (the 512-bit
// product replaced by x * y / d). The mode picks floor or ceiling, so a mode that is not a
// constant leaves the direction open: x=1, y=1, d=2 gives 0 under Floor and 1 under Ceil.
library Math {
    enum Rounding { Down, Up, Zero }

    function mulDiv(uint256 x, uint256 y, uint256 d) internal pure returns (uint256) {
        return x * y / d;
    }

    function mulDiv(uint256 x, uint256 y, uint256 d, Rounding rounding) internal pure returns (uint256) {
        uint256 result = mulDiv(x, y, d);
        if (rounding == Rounding.Up && mulmod(x, y, d) > 0) {
            result += 1;
        }
        return result;
    }
}

contract RoundingFlagV4 {
    function v4Free(uint256 x, uint256 y, uint256 d, Math.Rounding r) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, r);
    }

    function v4Down(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, Math.Rounding.Down);
    }

    function v4Up(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, Math.Rounding.Up);
    }
}
