// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// OpenZeppelin's mulDiv with a rounding mode, in its v5 and v4.9 shapes (the 512-bit
// product replaced by x * y / d). The mode picks floor or ceiling, so a mode that is not a
// constant leaves the direction open: x=1, y=1, d=2 gives 0 under Floor and 1 under Ceil.
library SafeCast {
    function toUint(bool b) internal pure returns (uint256 u) {
        assembly {
            u := iszero(iszero(b))
        }
    }
}

library Math {
    enum Rounding { Floor, Ceil, Trunc, Expand }

    function mulDiv(uint256 x, uint256 y, uint256 d) internal pure returns (uint256) {
        return x * y / d;
    }

    function unsignedRoundsUp(Rounding rounding) internal pure returns (bool) {
        return uint8(rounding) % 2 == 1;
    }

    function mulDiv(uint256 x, uint256 y, uint256 d, Rounding rounding) internal pure returns (uint256) {
        return mulDiv(x, y, d) + SafeCast.toUint(unsignedRoundsUp(rounding) && mulmod(x, y, d) > 0);
    }
}

contract RoundingFlag {
    // the mode is a free input: either side is possible
    function v5Free(uint256 x, uint256 y, uint256 d, Math.Rounding r) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, r);
    }

    function v5Floor(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, Math.Rounding.Floor);
    }

    function v5Ceil(uint256 x, uint256 y, uint256 d) public pure returns (uint256) {
        return Math.mulDiv(x, y, d, Math.Rounding.Ceil);
    }
}
