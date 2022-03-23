package ch.bonq.borrowingprotocol

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class tptamm {

    class Amm(var beur: Double = 0.0, var tpt: Double = 0.0) {
        val fee = 0.003
        var tptFees: Double = 0.0
        var beurFees: Double = 0.0

        fun price(): Double {
            return beur / tpt
        }

        fun getBeur(tptAmount: Double): Double {
            val beurAmount = tptAmount * (beur / tpt) * (1 - fee)
            tptFees += tptAmount * fee
            tpt += tptAmount
            beur -= beurAmount
            return beurAmount
        }

        fun getTpt(beurAmount: Double): Double {
            val tptAmount = beurAmount * (tpt / beur) * (1 - fee)
            beurFees += beurAmount * fee
            beur += beurAmount
            tpt -= tptAmount
            return tptAmount
        }

        fun addLiquidity(beurAmount: Double, tptAmount: Double) {
//            require(beurAmount / tptAmount == beur / tpt) {"the ratio must be the same"}
            beur += beurAmount
            tpt += tptAmount
        }

    }

    @Test
    fun priceEvolution() {
        val amm = Amm(100000.0, 1000000.0)
        val ratio = 0.85
        while(amm.beur < 100100000.0) {
            val tpt = amm.getTpt(100.0)
            val beur = amm.getBeur(tpt * ratio)
            amm.addLiquidity(beur, beur / amm.price())
        }
        println("supply tpt: ${amm.tpt} beur: ${amm.beur} price: ${amm.price()}")
        println("fees tpt: ${amm.tptFees} beur: ${amm.beurFees}")
    }
}
