// SPDX-License-Identifier: MIT
contract EnumCompare {
    enum Mode { Down, Up }

    Mode stored;

    function set(Mode m) external {
        stored = m;
    }

    // The guard compares a parameter the calling context pins to a constant against a
    // storage value the analysis cannot pin down: the equality may or may not hold, so
    // neither branch is dead and the result covers both rounding directions.
    function maybeStored(Mode mode, uint256 a, uint256 b) internal view returns (uint256) {
        if (mode == stored) {
            return (a + b - 1) / b; // rounds up
        }
        return a / b; // rounds down
    }

    // The guard compares the pinned constant against a different literal: the equality
    // never holds, the round-up branch is dead, and only the round-down division remains.
    function neverLiteral(Mode mode, uint256 a, uint256 b) internal pure returns (uint256) {
        if (mode == Mode.Down) {
            return (a + b - 1) / b;
        }
        return a / b;
    }

    // The guard compares the pinned constant against another parameter the caller leaves
    // free: the equality may or may not hold, so neither branch is dead.
    function maybeParam(Mode mode, Mode other, uint256 a, uint256 b) internal pure returns (uint256) {
        if (mode == other) {
            return (a + b - 1) / b;
        }
        return a / b;
    }

    function callStored(uint256 a, uint256 b) external view returns (uint256) {
        return maybeStored(Mode.Up, a, b);
    }

    function callNever(uint256 a, uint256 b) external pure returns (uint256) {
        return neverLiteral(Mode.Up, a, b);
    }

    function callParam(Mode other, uint256 a, uint256 b) external pure returns (uint256) {
        return maybeParam(Mode.Up, other, a, b);
    }

    function pick(uint256 x) internal pure returns (Mode) {
        if (x > 0) {
            return Mode.Up;
        }
        return Mode.Down;
    }

    // The guard compares the pinned constant against a call result that can be either
    // enum value: the equality may hold, so neither branch is dead.
    function maybeCall(Mode mode, uint256 x, uint256 a, uint256 b) internal pure returns (uint256) {
        if (mode == pick(x)) {
            return (a + b - 1) / b;
        }
        return a / b;
    }

    function callPick(uint256 x, uint256 a, uint256 b) external pure returns (uint256) {
        return maybeCall(Mode.Up, x, a, b);
    }
}
