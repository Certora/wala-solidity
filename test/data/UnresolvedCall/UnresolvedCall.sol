// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// A call through an interface to a contract the analysis is not told about has no
// callee. Its result is the same in both runs only if every argument is.
interface IAccountant {
    function sync(uint256 st, uint256 jt) external view returns (uint256);
    function price() external view returns (uint256);
}

contract Kernel {
    IAccountant public immutable accountant;
    uint256 internal a;
    uint256 internal b;
    uint256 internal s;

    constructor(IAccountant acc) {
        accountant = acc;
    }

    function nav() internal view returns (uint256) {
        return a * s / b; // Down
    }

    function viaRounded() public view returns (uint256) {
        return accountant.sync(nav(), a); // rounded argument: Inconsistent
    }

    function viaExact() public view returns (uint256) {
        return accountant.sync(a, b); // exact arguments: Neither
    }

    function viaPrice() public view returns (uint256) {
        return accountant.price(); // no arguments: Neither
    }

    // branches on what the unknown contract makes of a rounded value
    function maxWithdraw() public view returns (uint256) {
        uint256 n = nav();
        if (accountant.sync(n, a) != 0) {
            return 0;
        }
        return n; // Inconsistent
    }

    function scaledPrice() public view returns (uint256) {
        return accountant.price() * a / b; // Down: the call itself is exact
    }

    function narrow() public view returns (uint128) {
        return uint128(nav()); // a cast is not a call: Down
    }
}
