// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// A unit suffix is part of the literal's value: `1 weeks` is 604800, so dividing by it rounds.
contract UnitSuffix {
    function perWeek(uint256 t) public pure returns (uint256) {
        return t / 1 weeks;
    }

    function perDay(uint256 t) public pure returns (uint256) {
        return t / 3 days;
    }

    function perHour(uint256 t) public pure returns (uint256) {
        return t / 2 hours;
    }

    function perMinute(uint256 t) public pure returns (uint256) {
        return t / 1 minutes;
    }

    function inEther(uint256 x) public pure returns (uint256) {
        return x / 1 ether;
    }

    function halfEther(uint256 x) public pure returns (uint256) {
        return x / 0.5 ether;
    }

    function inGwei(uint256 x) public pure returns (uint256) {
        return x / 1 gwei;
    }

    // still exact: one second and one wei are 1
    function perSecond(uint256 t) public pure returns (uint256) {
        return t / 1 seconds;
    }

    function inWei(uint256 x) public pure returns (uint256) {
        return x / 1 wei;
    }
}
