/*
 * Copyright © 2016-2019 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of this software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

package com.jelurida.ardor.contracts;

import nxt.addons.*;
import nxt.dgs.DigitalGoodsTransactionType;
import nxt.http.GetDGSGood;
import nxt.http.callers.DgsPurchaseCall;
import nxt.http.callers.GetDGSGoodCall;
import nxt.http.callers.SendMoneyCall;
import nxt.http.responses.TransactionResponse;
import nxt.util.Time;

import java.math.BigInteger;

import static nxt.blockchain.TransactionTypeEnum.PARENT_PAYMENT;
import static nxt.blockchain.TransactionTypeEnum.CHILD_PAYMENT;

/**
 * Sample contract which receives amount from the trigger transaction and returns a random amount between 0 and twice the received amount.
 * Warning:
 * This design is inappropriate for gambling applications. The reason is that users can trigger this contract using a phased
 * transaction and later not approve the trigger and response transactions in case they do not like the results.
 * For a better approach to gambling application see the AllForOnePayment sample contract.
 */
public class Escrow extends AbstractContract {

    @ContractParametersProvider
    public interface Params {
        @ContractInvocationParameter
        String goods();

        @ContractInvocationParameter
        String buyerMsg();

    }

    /**
     * Process a payment transaction and send back random amount to the sender
     * @param context contract context
     */
    @Override
    @ValidateTransactionType(accept = { PARENT_PAYMENT, CHILD_PAYMENT }) // These are the transaction types accepted by the contract
    @ValidateContractRunnerIsRecipient() // Validate that the payment was made to the contract runner account
    @ValidateChain(reject = 3) // Do not process payments made on the AEUR chain (just example)
    public JO processTransaction(TransactionContext context) {
        TransactionResponse transaction = context.getTransaction();
        if (transaction.isPhased()) {
            // We cannot allow phased transaction for the escrow
            // Therefore in this case we just refund the same amount.
            SendMoneyCall sendMoneyCall = SendMoneyCall.create(transaction.getChainId()).
                    recipient(transaction.getSender()).
                    amountNQT(transaction.getAmount());
            return context.createTransaction(sendMoneyCall);
        }

        long buyerAddress = context.getSenderId();
        long amount = context.getAmountNQT();
        Params params = context.getParams(Params.class);

        if (params.goods().equals("") || params.goods().equals("[String]")) {
            context.logInfoMessage("No good sent, sending back payment");
            SendMoneyCall sendMoneyCall = SendMoneyCall.create(2).
                    recipient(buyerAddress).
                    amountNQT(amount);

            return context.createTransaction(sendMoneyCall);
        }
        int currentBlock = context.getBlockchainHeight();
        String goods = params.goods();
        JO getDGSGood = GetDGSGoodCall.create(context.getChainOfTransaction().getId()).
                goods(goods).
                call();

        if (!getDGSGood.isExist("priceNQT")) {
            context.logInfoMessage("WARNING: Could not get good, sending back payment");
            SendMoneyCall sendMoneyCall = SendMoneyCall.create(2).
                    recipient(buyerAddress).
                    amountNQT(amount);

            return context.createTransaction(sendMoneyCall);
        }
        long priceNQT = getDGSGood.getLong("priceNQT");
        if (priceNQT+500000000 > amount) {
            context.logInfoMessage("WARNING: Not enough sent, sending back payment");
            SendMoneyCall sendMoneyCall = SendMoneyCall.create(2).
                    recipient(buyerAddress).
                    amountNQT(amount);

            return context.createTransaction(sendMoneyCall);
        }
        context.logInfoMessage(String.format("INFO: price: %s |  amount: %s", priceNQT, amount));
        //Get delivery timestamp
        Time.EpochTime EPOCH_TIME = new Time.EpochTime();
        int TIME_SINCE_EPOCH = EPOCH_TIME.getTime();
        String deliveryTimestamp = String.valueOf(TIME_SINCE_EPOCH + 345600);

        JO message = new JO();
        message.put("buyerMsg", params.buyerMsg());

        // Forward on purchase
        DgsPurchaseCall dgsPurchaseCall = DgsPurchaseCall.create(context.getChainOfTransaction().getId()).
                goods(goods).
                message(message.toJSONString()).
                messageIsPrunable(true).
                deliveryDeadlineTimestamp(deliveryTimestamp).
                priceNQT(priceNQT).
                quantity("1").
                phased(true).
                //phasingVotingModel((byte)6).
                phasingParams("{\"phasingQuorum\": \"1\",\"phasingExpression\": \"Buyer | !Escro\",\"phasingHolding\": \"0\",\"phasingMinBalance\": \"0\",\"phasingMinBalanceModel\": 0,\"phasingSubPolls\": {\"Escro\": {\"phasingHolding\": \"0\",\"phasingQuorum\": \"1\",\"phasingWhitelist\": [\"8984186822627297858\"],\"phasingMinBalance\": \"0\",\"phasingMinBalanceModel\": 0,\"phasingVotingModel\": 0},\"Buyer\": {\"phasingHolding\": \"0\",\"phasingQuorum\": \"1\",\"phasingWhitelist\": [\""+buyerAddress+"\"],\"phasingMinBalance\": \"0\",\"phasingMinBalanceModel\": 0,\"phasingVotingModel\": 0}},\"phasingVotingModel\": 6,\"chain\": \"2\"}").
                phasingFinishHeight(currentBlock+20100); //Max amount of blocks, on testnet ~4 days
        return context.createTransaction(dgsPurchaseCall);
    }
}