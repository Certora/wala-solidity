// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// Repro: directions from a tuple-returning callee are lost in the caller.
// Every helper rounds Down in an all-exact context, so every entry point should be Down.
contract TupleDestructure {
    function pair(uint256 a, uint256 b, uint256 c) internal pure returns (uint256 x, uint256 y) {
        x = a * b / c;
        y = a / c;
    }

    function pairExplicit(uint256 a, uint256 b, uint256 c) internal pure returns (uint256, uint256) {
        return (a * b / c, a / c);
    }

    function one(uint256 a, uint256 c) internal pure returns (uint256) {
        return a / c;
    }

    // --- tuple callee, tuple caller ---
    // T1: named returns, destructure, fall off the end
    function t1(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        (x, y) = pair(a, b, c);
    }

    // T2: named returns, early return (0,0), destructure, fall off (the Cork shape)
    function t2(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        if (c == 0) return (0, 0);
        (x, y) = pair(a, b, c);
    }

    // T3: forward the call directly
    function t3(uint256 a, uint256 b, uint256 c) public pure returns (uint256, uint256) {
        return pair(a, b, c);
    }

    // T4: destructure into locals, explicit return
    function t4(uint256 a, uint256 b, uint256 c) public pure returns (uint256, uint256) {
        (uint256 p, uint256 q) = pair(a, b, c);
        return (p, q);
    }

    // T5: destructure into named returns, explicit return of them
    function t5(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        (x, y) = pair(a, b, c);
        return (x, y);
    }

    // T6: callee with an explicit tuple return, caller like T1
    function t6(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        (x, y) = pairExplicit(a, b, c);
    }

    // --- tuple callee, scalar caller (ledger item 4) ---
    function m1(uint256 a, uint256 b, uint256 c) public pure returns (uint256) {
        (uint256 p, ) = pair(a, b, c);
        return p;
    }

    // M2: use a component in further arithmetic
    function m2(uint256 a, uint256 b, uint256 c) public pure returns (uint256) {
        (uint256 p, uint256 q) = pair(a, b, c);
        return p + q;
    }

    // --- scalar callee controls ---
    function s1(uint256 a, uint256 c) public pure returns (uint256 x) {
        x = one(a, c);
    }

    function s2(uint256 a, uint256 c) public pure returns (uint256 x) {
        if (c == 0) return 0;
        x = one(a, c);
    }

    function s3(uint256 a, uint256 c) public pure returns (uint256) {
        return one(a, c);
    }

    // --- no callee: tuple built in place, Cork shape ---
    function n1(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        if (c == 0) return (0, 0);
        x = a * b / c;
        y = a / c;
    }

    function n2(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        if (c == 0) return (0, 0);
        (x, y) = (a * b / c, a / c);
    }

    // --- single-exit return lowering ---
    // R1: returning a permutation of the named return variables (expected (Neither, Down))
    function r1(uint256 a, uint256 b) public pure returns (uint256 x, uint256 y) {
        x = a / b;
        y = a;
        return (y, x);
    }

    // R2: early return, then return a tuple-valued call (expected (Down, Down))
    function r2(uint256 a, uint256 b, uint256 c) public pure returns (uint256 x, uint256 y) {
        if (c == 0) return (0, 0);
        return pair(a, b, c);
    }

    // R3: swap via destructuring a tuple literal (expected (Neither, Down))
    function r3(uint256 a, uint256 b) public pure returns (uint256 x, uint256 y) {
        x = a / b;
        y = a;
        (x, y) = (y, x);
    }
}
