package ch.bonq.borrowingprotocol

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class TroveFactory(val fee: BigDecimal = BigDecimal("0.005")) {
    val troves = HashMap<String, Trove>()
    val collateralTokens = HashMap<String, Token>()
    // Stakes by users. The key is the user's address
    val bonqStakes = HashMap<String, BigDecimal>()
    val spStakes = HashMap<String, BigDecimal>()
    var totalDebt = BigDecimal("0")
    var feesPaid = BigDecimal("0")
    val stableCoin = Token("BEUR", "BONQ EUR", BigDecimal("1.0"))
    val bonq = Token("BONQ", "BONQ Token", BigDecimal("2.0"))
    var baseFee = 0.0

    val totalBonqStake: BigDecimal
        get() {
            var total = BigDecimal(0)
            for((_, v) in bonqStakes) {
                total += v
            }
            return total
        }

    val totalSPStake: BigDecimal
        get() {
            var total = BigDecimal(0)
            for((_, v) in spStakes) {
                total += v
            }
            return total
        }

    init {
        collateralTokens.set("BONQ", bonq)
        collateralTokens.set("WEWT", Token("WEWT", "Wrapped EWT", BigDecimal("10.0")))
        collateralTokens.set("ALBT", Token("ALBT", "Alliance Block Token", BigDecimal("10.0")))
    }

    fun createTrove(owner: String, token: Token): Trove {
        if(troves[owner + token.shortName] == null) troves.set(owner + token.shortName, Trove(owner, token, this))
        else throw IllegalStateException("the owner ${owner} already has a trove for ${token.longName}")
        return troves[owner + token.shortName]!!
    }

    fun setTokenPrice(tokenName: String, price: BigDecimal) {
        val token = collateralTokens[tokenName]!!
        token.price = price
        val trovesToLiquidate = ArrayList<Trove>()
        for((_, trove) in troves) {
            if(trove.tcr <= trove.collateralToken.mcr) trovesToLiquidate.add(trove)
        }
        for(trove in trovesToLiquidate) {
            trove.liquidate("anonymous")
        }
    }

    // Collateral per token the key is the short name of the token
    fun totalCollateral(tokenName: String): BigDecimal {
        var total = BigDecimal("0")
        for((_, v) in troves) {
            if(tokenName.equals(v.collateralToken.shortName)) {
                total += v.collateralToken.balances[v.address]!!
            }
        }
        return total
    }

    fun distributeFees(fee: BigDecimal) {
        this.feesPaid += fee
        for((k, v) in bonqStakes) {
            stableCoin.transfer("mint", k, fee.multiply(v.divide(totalBonqStake)))
        }
    }

    fun stakeBEur(staker: String, amount: BigDecimal) {
        stableCoin.transfer(staker, "factory", amount)
        spStakes.set(staker, amount)
    }

    fun stakeBonq(staker: String, amount: BigDecimal) {
        bonq.transfer(staker, "factory", amount)
        if(bonqStakes[staker] == null) bonqStakes[staker] = BigDecimal("0")
        bonqStakes[staker] = bonqStakes[staker]!! + amount
    }

    fun unStakeBonq(staker: String, amount: BigDecimal) {
        val realAmount = bonqStakes[staker]!!.min(amount)
        bonq.transfer("factory", staker, realAmount)
        bonqStakes[staker] = bonqStakes[staker]!! - realAmount
    }
}

class Token(val shortName: String, val longName: String, var price: BigDecimal, var mcr: BigDecimal = BigDecimal("1.2")) {
    val balances = HashMap<String, BigDecimal>()
    var totalSupply = BigDecimal.valueOf(0.0)

    fun transfer(sender: String, recipient: String, amount: BigDecimal) {
        if(balances[sender] == null) balances.set(sender, BigDecimal("0"))
        if(balances[recipient] == null) balances.set(recipient, BigDecimal("0"))

        if(balances[sender]!! < amount) {
            totalSupply = totalSupply.add(balances[sender]!!.subtract(amount).abs())
        }
        balances[sender] = balances[sender]!!.subtract(amount).max(BigDecimal.valueOf(0))
        balances[recipient] = balances[recipient]!!.add(amount)
    }

    fun burn(sender: String, amount: BigDecimal) {
        require(balances[sender]!! >= amount) {"the sender must have enough tokens to burn"}
        balances[sender] = balances[sender]!!.subtract(amount)
        totalSupply = totalSupply.subtract(amount)
    }
}

class Trove(val owner: String, val collateralToken: Token, val factory: TroveFactory) {
    val address = owner + "trove"
    val collateral: BigDecimal
        get() = collateralToken.balances[address]!!
    var liquidationReserve = BigDecimal("0")
    var debt = BigDecimal("0")

    init {
        collateralToken.balances.set(address, BigDecimal("0"))
    }

    val tcr: BigDecimal
        get() = if (debt.compareTo(BigDecimal(0.0)) != 0)
                collateral.multiply(collateralToken.price)
                    .divide(debt, 128, RoundingMode.HALF_EVEN).setScale(64, RoundingMode.HALF_EVEN)
            else BigDecimal("0")

    fun borrow(amount: BigDecimal) {
        require(amount > BigDecimal("0")) { "the amount to borrow must be positive" }
        require(collateral.multiply(collateralToken.price)
            .divide(debt + amount, 128, RoundingMode.HALF_EVEN) >=
                collateralToken.mcr) {"the TCR must not be lower than MCR"}
        if(liquidationReserve.compareTo(BigDecimal("0")) == 0) {
            liquidationReserve = BigDecimal("1")
            factory.stableCoin.transfer("mint", address, liquidationReserve)
        }
        val fee = amount.multiply(factory.fee)
        debt += amount + fee
        factory.stableCoin.transfer("mint", owner, amount)
        factory.distributeFees(fee)
        factory.totalDebt += amount + fee
    }

    fun repay(amount: BigDecimal): BigDecimal {
        require(amount > BigDecimal("0")) { "the amount to repay must be positive" }
        val repayAmount = amount.min(debt)
        factory.stableCoin.burn(owner, repayAmount)
        factory.totalDebt -= repayAmount
        debt -= repayAmount
        if(debt.compareTo(BigDecimal("0")) == 0) {
            factory.stableCoin.burn(address, liquidationReserve)
            liquidationReserve = BigDecimal("0")
        }
        return repayAmount
    }

    fun liquidate(caller: String) {
        require(tcr <= collateralToken.mcr) {"only undercollateralized troves can be liquidated"}
        factory.troves.remove(owner + collateralToken.shortName)

        factory.stableCoin.transfer(address, caller, liquidationReserve)

        debt += liquidationReserve
        if(factory.totalSPStake.compareTo(BigDecimal("0")) == 1) {
            val totalStake = factory.totalSPStake
            val amount = totalStake.min(debt)
            val collateralToTransfer = collateral.multiply(amount.divide(debt, 128, RoundingMode.HALF_EVEN))
            debt -= amount

            for((staker, stake) in factory.spStakes) {
                val prorata = stake.divide(totalStake, 128, RoundingMode.HALF_EVEN)
                collateralToken.transfer(address, staker, collateralToTransfer.multiply(prorata))
                factory.spStakes[staker] = factory.spStakes[staker]!!.subtract(amount.multiply(prorata))
            }
        }
        // if there is some debt left over from the stability pool liquidation, then spread it over the other trove holders
        if(debt.compareTo(BigDecimal("0")) == 1){
            // we have to remember the collateral before starting to transfer it because it is a dynamic value
            val coll = collateral
            val totalCollateral = factory.totalCollateral(collateralToken.shortName)
            for((_, trove) in factory.troves) {
                val prorata = trove.collateral.divide(totalCollateral, 128, RoundingMode.HALF_EVEN)
                trove.debt += debt.multiply(prorata)
                collateralToken.transfer(address, trove.address, coll.multiply(prorata))
            }
        }
        debt = BigDecimal("0")
        liquidationReserve = BigDecimal("0")
    }
}
