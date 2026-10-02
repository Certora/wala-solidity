// SPDX-License-Identifier: MIT
contract YulBuiltins {
    // shl(s, v) is v << s: a doubling of the rounded quotient still rounds down.
    function shlQuotient(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := shl(1, div(a, b)) }
    }

    // sar(s, v) is v >> s: a halving of the rounded quotient still rounds down.
    function sarQuotient(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := sar(1, div(a, b)) }
    }

    // sdiv is a division; its result rounds down (signedness is not modelled).
    function sdivQuotient(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := sdiv(a, b) }
    }

    // xor of a rounded operand is not a numeric function of magnitudes: Inconsistent.
    // Before these builtins were translated, the value was silently severed to null.
    function xorRounded(uint256 a, uint256 b, uint256 m) public pure returns (uint256 r) {
        assembly { r := xor(div(a, b), m) }
    }

    // exp currently erases operand rounding to exact (the POW gap in the ledger);
    // this locks the present behavior so a change to it is deliberate.
    function expQuotient(uint256 a, uint256 b) public pure returns (uint256 r) {
        assembly { r := exp(div(a, b), 2) }
    }

    // addmod flows through mod, which is treated as exact (ledger); not null anymore.
    function addmodPass(uint256 a, uint256 b, uint256 n) public pure returns (uint256 r) {
        assembly { r := addmod(a, b, n) }
    }

    // slt as a 0/1 indicator over exact operands stays exact.
    function sltIndicator(uint256 x, uint256 y) public pure returns (uint256 r) {
        assembly { r := slt(x, y) }
    }
}
