package ch.bonq.borrowingprotocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.math.RoundingMode

@SpringBootTest
class BorrowingProtocolApplicationTests {

    @Test
    fun troveCreation(factory: TroveFactory = TroveFactory(), owner: String = "aOwner"): TroveFactory {
        assertNotNull(factory)

        val trove = factory.createTrove(owner, factory.collateralTokens["BONQ"]!!)

        var exception = assertThrows<IllegalArgumentException> { trove.borrow(BigDecimal(0)) }
        assertEquals("the amount to borrow must be positive", exception.message)

        exception = assertThrows<IllegalArgumentException> { trove.borrow(BigDecimal(1000)) }
        assertEquals("the TCR must not be lower than MCR", exception.message)

        factory.bonq.transfer(owner, trove.address, BigDecimal("1000"))
        assertEquals(BigDecimal("1000"), factory.bonq.balances[trove.address])

        trove.borrow(BigDecimal("1000"))
        assertEquals(BigDecimal("1005"), trove.debt.setScale(0, RoundingMode.HALF_EVEN))
        assertEquals(BigDecimal("1000"), factory.stableCoin.balances[owner])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances[trove.address])

        trove.borrow(BigDecimal("500"))
        assertEquals(BigDecimal("1507.5"), trove.debt.setScale(1, RoundingMode.HALF_EVEN))
        assertEquals(BigDecimal("1500"), factory.stableCoin.balances[owner])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances[trove.address])

        return factory
    }

    @Test
    fun repay() {
        val factory = troveCreation()
        val trove = factory.troves["aOwnerBONQ"]!!
        assertEquals("aOwner", trove.owner)
        assertEquals(BigDecimal("1500"), factory.stableCoin.balances["aOwner"])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances[trove.address])

        factory.stableCoin.transfer("mint", "aOwner", BigDecimal("7.5"))

        var repayAmount = BigDecimal("757.5")
        var ownerBalance = factory.stableCoin.balances["aOwner"]!!
        trove.repay(repayAmount)
        assertEquals(BigDecimal("750.0"), trove.debt.setScale(1, RoundingMode.HALF_EVEN))
        assertEquals(ownerBalance.subtract(repayAmount), factory.stableCoin.balances["aOwner"])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances[trove.address])

        ownerBalance = factory.stableCoin.balances["aOwner"]!!
        repayAmount = trove.repay(BigDecimal("500"))
        assertEquals(BigDecimal("250.0"), trove.debt.setScale(1, RoundingMode.HALF_EVEN))
        assertEquals(ownerBalance.subtract(repayAmount), factory.stableCoin.balances["aOwner"])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances[trove.address])

        ownerBalance = factory.stableCoin.balances["aOwner"]!!
        repayAmount = trove.repay(BigDecimal("500"))
        assertEquals(BigDecimal("0.0"), trove.debt.setScale(1, RoundingMode.HALF_EVEN))
        assertEquals(ownerBalance.subtract(repayAmount), factory.stableCoin.balances["aOwner"])
        assertEquals(
            BigDecimal("0.0"),
            factory.stableCoin.balances[trove.address]!!.setScale(1, RoundingMode.HALF_EVEN)
        )
    }

    @Test
    fun liquidateOneToCommunity() {
        val factory = TroveFactory()
        troveCreation(factory, "aOwner")
        val troveA = factory.troves["aOwnerBONQ"]!!
        troveCreation(factory, "bOwner")
        val troveB = factory.troves["bOwnerBONQ"]!!

        troveA.repay(BigDecimal("500"))
        assertEquals(BigDecimal("2000"), factory.totalCollateral("BONQ"))

        val aDebt = troveA.debt
        val bDebt = troveB.debt
        factory.setTokenPrice(
            "BONQ", troveB.debt
                .divide(troveB.collateral, 128, RoundingMode.HALF_EVEN).multiply(BigDecimal("1.2"))
        )

        assertNull(factory.troves["bOwnerBONQ"])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances["anonymous"])
        assertEquals(aDebt.add(bDebt).add(BigDecimal(("1"))), troveA.debt.setScale(3, RoundingMode.HALF_EVEN))
    }

    fun prepareManyTroves(): Triple<TroveFactory, List<Trove>, BigDecimal> {
        val factory = TroveFactory()
        val troves = ArrayList<Trove>()
        // create 10 troves. 8 of them with 1'000 of debt and 2 with 1'500
        for (i in 1..120) {
            troveCreation(factory, "owner${i}")
            factory.troves["owner${i}BONQ"]!!.repay(BigDecimal("7.5"))
            if (!(i >= 30 && i <= 39) && !(i >= 70 && i <= 79)) {
                troves.add(factory.troves["owner${i}BONQ"]!!)
                factory.troves["owner${i}BONQ"]!!.repay(BigDecimal("500"))
            }
        }
        assertEquals(BigDecimal("120000"), factory.totalCollateral("BONQ"))
        assertEquals(BigDecimal("130000"), factory.totalDebt.setScale(0, RoundingMode.HALF_EVEN))

        val troveToLiquidate = factory.troves["owner30BONQ"]!!
        val liquidationPrice = troveToLiquidate.debt.divide(troveToLiquidate.collateral, 128, RoundingMode.HALF_EVEN)
            .multiply(BigDecimal("1.2"))
        return Triple(factory, troves, liquidationPrice)
    }

    @Test
    fun liquidateManyToCommunity() {
        val (factory, troves, liquidationPrice) = prepareManyTroves()


        factory.setTokenPrice("BONQ", liquidationPrice)

        assertEquals(BigDecimal("20"), factory.stableCoin.balances["anonymous"])

        for (trove in troves) {
            assertEquals(BigDecimal("1300.200"), trove.debt.setScale(3, RoundingMode.HALF_EVEN))
        }
    }

    @Test
    fun getFees() {
        val factory = TroveFactory()
        for (i in 1..10) {
            factory.stakeBonq("staker${i}", BigDecimal("100"))
        }

        for (i in 1..10) {
            troveCreation(factory, "owner${i}")
        }

        for (i in 1..10) {
            assertEquals(BigDecimal("7.5000"), factory.stableCoin.balances["staker${i}"])
        }
    }

    @Test
    fun liquidateOneToStabilityPool() {
        val factory = TroveFactory()
        troveCreation(factory, "aOwner")
        val troveA = factory.troves["aOwnerBONQ"]!!
        troveCreation(factory, "bOwner")
        val troveB = factory.troves["bOwnerBONQ"]!!

        factory.bonq.transfer("aOwner", troveA.address, BigDecimal("1000"))
        troveA.borrow(BigDecimal("1250"))
        assertEquals(BigDecimal("3000"), factory.totalCollateral("BONQ"))

        factory.stakeBEur("aOwner", BigDecimal("2000"))

        val stake = factory.spStakes["aOwner"]!!
        val bDebt = troveB.debt
        factory.setTokenPrice(
            "BONQ", troveB.debt
                .divide(troveB.collateral, 128, RoundingMode.HALF_EVEN).multiply(BigDecimal("1.2"))
        )

        assertNull(factory.troves["bOwnerBONQ"])
        assertEquals(BigDecimal("1"), factory.stableCoin.balances["anonymous"])
        assertEquals(stake.subtract(bDebt).subtract(BigDecimal("1")), factory.spStakes["aOwner"]!!.setScale(3))
        assertEquals(BigDecimal("1000"), factory.bonq.balances["aOwner"]!!.setScale(0))
    }

    @Test
    fun liquidateManyToStabilityPoolAndOverflowToCommunity() {
        val (factory, troves, liquidationPrice) = prepareManyTroves()
        for(trove in troves) {
            factory.bonq.transfer("aOwner", trove.address, BigDecimal("1000"))
            trove.borrow(BigDecimal("1250"))
            factory.stakeBEur(trove.owner, BigDecimal("2000"))
        }

        val liquidatedDebt = factory.troves["owner30BONQ"]!!.debt
            .add(BigDecimal("1")).multiply(BigDecimal("20")).divide(BigDecimal("100")).setScale(3)
        assertNull(factory.stableCoin.balances["anonymous"])
        factory.setTokenPrice("BONQ", liquidationPrice)
        assertEquals(BigDecimal("20"), factory.stableCoin.balances["anonymous"])

        for(trove in troves) {
            assertEquals(BigDecimal("200.000"), factory.bonq.balances[trove.owner]!!.setScale(3))
            assertEquals(BigDecimal("2000").subtract(liquidatedDebt), factory.spStakes[trove.owner]!!.setScale(3))
        }
    }
}
