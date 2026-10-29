// SPDX-License-Identifier: MIT
contract VirtualShares {
    // The ERC4626 virtual-shares conversion: a plain floor. The literal 1 appears in both
    // the dividend and the divisor, but a shared constant is not a round-up bias.
    function convertToShares(uint256 assets, uint256 supply, uint256 totalAssets)
        public pure returns (uint256)
    {
        return assets * (supply + 1) / (totalAssets + 1);
    }

    // The genuine bias idiom: the divisor itself flows into the dividend's addition.
    function biasDiv(uint256 a, uint256 b) public pure returns (uint256) {
        return (a + b - 1) / b;
    }

    // Shared storage-derived addend, same shape as the constant case.
    uint256 private fee;

    function withFee(uint256 assets, uint256 supply, uint256 totalAssets)
        public view returns (uint256)
    {
        return assets * (supply + fee) / (totalAssets + fee);
    }
}
