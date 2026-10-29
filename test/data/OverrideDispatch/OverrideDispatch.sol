// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// Royco's quoter shape: the deployed contract (OverrideDispatch) inherits each rate through a chain of
// overrides and a sibling base. Solidity dispatches to the most-derived override (here
// Top's exact rates), so every convert* is exact. Each overridden level rounds, so a call
// dispatched to any of them shows up as a non-exact verdict.

abstract contract Quoter {
    uint256 internal a;
    uint256 internal b;

    function rateA() public view virtual returns (uint256) { return (a + b - 1) / b; }
    function rateB() public view virtual returns (uint256) { return (a + b - 1) / b; }
    function rateC() public view virtual returns (uint256) { return (a + b - 1) / b; }

    function convertA(uint256 x) public view returns (uint256) { return x + rateA(); }
    function convertB(uint256 x) public view returns (uint256) { return x + rateB(); }
    function convertC(uint256 x) public view returns (uint256) { return x + rateC(); }
}

abstract contract Level1 is Quoter {
    function rateA() public view virtual override returns (uint256) { return a / b; }
    function rateB() public view virtual override returns (uint256) { return a / b; }
    function rateC() public view virtual override returns (uint256) { return a / b; }
}

abstract contract Level2 is Level1 {
    function rateA() public view virtual override returns (uint256) { return (a + 1) / b; }
    function rateB() public view virtual override returns (uint256) { return (a + 1) / b; }
    function rateC() public view virtual override returns (uint256) { return (a + 1) / b; }
}

abstract contract Level3 is Level2 {
    function rateA() public view virtual override returns (uint256) { return (a + 2) / b; }
    function rateB() public view virtual override returns (uint256) { return (a + 2) / b; }
    function rateC() public view virtual override returns (uint256) { return (a + 2) / b; }
}

abstract contract Sibling is Quoter {
}

abstract contract Top is Level3, Sibling {
    function rateA() public view virtual override(Quoter, Level3) returns (uint256) { return a; }
    function rateB() public view virtual override(Quoter, Level3) returns (uint256) { return a; }
    function rateC() public view virtual override(Quoter, Level3) returns (uint256) { return a; }
}

contract OverrideDispatch is Top {
}
