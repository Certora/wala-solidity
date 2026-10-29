// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// A struct member keeps the direction of the value written to it (Compound's Exp math).
contract StructField {
    uint256 cash;
    uint256 totalSupply;

    struct Exp { uint256 mantissa; }
    struct Pair { uint256 lo; uint256 hi; }

    function rate() internal view returns (uint256) {
        return cash * 1e18 / totalSupply; // Down
    }

    function divE(uint256 x, Exp memory e) internal pure returns (uint256) {
        return x * 1e18 / e.mantissa;
    }

    function mk() internal view returns (Exp memory) {
        return Exp({mantissa: rate()});
    }

    // dividing by a Down rate pushes up, the floor pushes down
    function inline_(uint256 x) external view returns (uint256) {
        uint256 r = rate();
        return x * 1e18 / r;
    }

    function localStruct(uint256 x) external view returns (uint256) {
        Exp memory e = Exp({mantissa: rate()});
        return x * 1e18 / e.mantissa;
    }

    function helperStruct(uint256 x) external view returns (uint256) {
        return divE(x, Exp({mantissa: rate()}));
    }

    // members are matched by name, not position
    function namedLo(uint256 x) external view returns (uint256) {
        Pair memory p = Pair({hi: rate(), lo: x});
        return p.lo; // exact
    }

    function namedHi(uint256 x) external view returns (uint256) {
        Pair memory p = Pair({hi: rate(), lo: x});
        return p.hi; // Down
    }

    function positionalHi(uint256 x) external view returns (uint256) {
        Pair memory p = Pair(x, rate());
        return p.hi; // Down
    }

    // a later write is seen by later reads, not earlier ones
    function written(uint256 x) external pure returns (uint256) {
        Exp memory e = Exp(x);
        e.mantissa = x / 3;
        return e.mantissa; // Down
    }

    function beforeWrite(uint256 x) external pure returns (uint256) {
        Exp memory e = Exp(x);
        uint256 old = e.mantissa;
        e.mantissa = x / 3;
        return old; // exact
    }

    // rebuilt every iteration: last iteration's write is to another struct
    function loopFresh(uint256 x, uint256 n) external pure returns (uint256 total) {
        for (uint256 i = 0; i < n; i++) {
            Exp memory e = Exp(x);
            total += e.mantissa;
            e.mantissa = x / 3;
        }
        // exact
    }

    // built once: last iteration's write is seen
    function loopCarried(uint256 x, uint256 n) external pure returns (uint256 total) {
        Exp memory e = Exp(x);
        for (uint256 i = 0; i < n; i++) {
            total += e.mantissa;
            e.mantissa = x / 3;
        }
        // Down
    }

    function returned() external view returns (uint256) {
        return mk().mantissa; // Down
    }

    // a ceiling written with two reads of the same member
    function ceil(uint256 x, Exp memory e) public pure returns (uint256) {
        return (x * 1e18 + e.mantissa - 1) / e.mantissa; // Up
    }
}
