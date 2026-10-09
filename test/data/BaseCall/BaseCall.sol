// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// An explicit base-contract call `Base.f()` is bound statically to Base's body: no
// virtual dispatch, so the most derived override must not be chosen and the call must
// not be dropped.
contract Base {
    uint256 internal a;
    uint256 internal b;

    function rate() public view virtual returns (uint256) {
        return a / b; // Down
    }

    function scaled(uint256 x) internal view virtual returns (uint256) {
        return x * 1000 / b; // Down
    }

    function share() internal view virtual returns (uint256) {
        return a; // exact here, overridden below
    }

    function total() public view virtual returns (uint256) {
        return share(); // virtual: dispatches on the calling contract
    }
}

contract Other {
    function rate() public view virtual returns (uint256) {
        return 7;
    }
}

contract Derived is Base, Other {
    // override that only forwards to a named base
    function rate() public view override(Base, Other) returns (uint256) {
        return Base.rate();
    }

    function scaled(uint256 x) internal view override returns (uint256) {
        return Base.scaled(x) + 1;
    }

    function useRate(uint256 x) public view returns (uint256) {
        return x / rate(); // divides by a Down rate: Inconsistent
    }

    function useScaled(uint256 x) public view returns (uint256) {
        return scaled(x); // Down
    }

    function share() internal view override returns (uint256) {
        return a / 3; // Down
    }

    // Base.total runs on this contract, so its share() is Derived.share
    function total() public view override returns (uint256) {
        return Base.total();
    }

    function viaOther() public view returns (uint256) {
        return Other.rate(); // exact
    }
}
