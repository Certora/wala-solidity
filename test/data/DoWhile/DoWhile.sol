// SPDX-License-Identifier: MIT
contract DoWhile {
    function pay(uint256 y, uint256 z) public pure returns (uint256) {
        uint256 bound = y / z;
        uint256 s = 0; uint256 i = 0;
        do { s += 1; i += 1; } while (i < bound);
        return s;
    }
}
