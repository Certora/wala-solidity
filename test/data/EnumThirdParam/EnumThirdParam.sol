// SPDX-License-Identifier: MIT
contract EnumThirdParam {
    enum Mode { Floor, Ceil }

    // The enum is deliberately the last parameter: the context machinery must pin the
    // constant to this position, not to whichever argument came first.
    function conv(uint256 a, uint256 b, Mode r) internal pure returns (uint256) {
        if (r == Mode.Ceil) {
            return (a + b - 1) / b;
        }
        return a / b;
    }

    function convUp(uint256 a, uint256 b) external pure returns (uint256) {
        return conv(a, b, Mode.Ceil);
    }

    function convDown(uint256 a, uint256 b) external pure returns (uint256) {
        return conv(a, b, Mode.Floor);
    }
}
