// SPDX-License-Identifier: MIT
pragma solidity 0.8.12;

library L {
    function half(uint256 a) internal pure returns (uint256) {
        return a / 2;
    }
}

contract Base {
    function f(uint256 x) public view virtual returns (uint256) {
        return x;
    }
}

contract SuperChain is Base {
    using L for uint256;

    // chained directly on the super-call result (the AToken.balanceOf shape)
    function f(uint256 x) public view override returns (uint256) {
        return super.f(x).half();
    }

    // same computation through a local (the AToken.totalSupply shape)
    function g(uint256 x) public view returns (uint256) {
        uint256 y = super.f(x);
        return y.half();
    }
}
