/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.core.genesis;

import co.rsk.config.RskSystemProperties;
import co.rsk.core.BlockDifficulty;
import co.rsk.core.RskAddress;
import co.rsk.core.bc.BlockChainImpl;
import co.rsk.core.bc.BlockExecutor;
import co.rsk.validators.BlockValidator;
import org.apache.commons.lang3.StringUtils;
import org.ethereum.core.*;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ReceiptStore;
import org.ethereum.listener.CompositeEthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.ethereum.crypto.HashUtil.EMPTY_TRIE_HASH;

/**
 * Created by mario on 13/01/17.
 */
@Component
public class BlockChainLoader {

    private static final Logger logger = LoggerFactory.getLogger("general");

    private final RskSystemProperties config;
    private final BlockStore blockStore;
    private final Repository repository;
    private final ReceiptStore receiptStore;
    private final TransactionPool transactionPool;
    private final CompositeEthereumListener listener;
    private final BlockValidator blockValidator;

    @Autowired
    public BlockChainLoader(
            RskSystemProperties config,
            org.ethereum.core.Repository repository,
            org.ethereum.db.BlockStore blockStore,
            ReceiptStore receiptStore,
            TransactionPool transactionPool,
            CompositeEthereumListener listener,
            BlockValidator blockValidator) {

        this.config = config;
        this.blockStore = blockStore;
        this.repository = repository;
        this.receiptStore = receiptStore;
        this.transactionPool = transactionPool;
        this.listener = listener;
        this.blockValidator = blockValidator;
    }

    public BlockChainImpl loadBlockchain() {
        BlockChainImpl blockchain = new BlockChainImpl(
                config,
                repository,
                blockStore,
                receiptStore,
                transactionPool,
                listener,
                blockValidator,
                new BlockExecutor(config, repository, receiptStore, blockStore, listener)
        );

        listener.addListener(new EthereumListenerAdapter() {
            private int nFlush = 0;
            @Override
            public void onBlock(Block block, List<TransactionReceipt> receipts) {
                // Rolling counter that helps doing flush every RskSystemProperties.CONFIG.flushNumberOfBlocks() flush attempts
                // We did this because flush is slow, and doing flush for every block degrades the node performance.
                if (config.isFlushEnabled() && nFlush == 0)  {
                    long saveTime = System.nanoTime();
                    repository.flush();
                    long totalTime = System.nanoTime() - saveTime;
                    logger.trace("repository flush: [{}]nano", totalTime);
                    saveTime = System.nanoTime();
                    blockStore.flush();
                    totalTime = System.nanoTime() - saveTime;
                    logger.trace("blockstore flush: [{}]nano", totalTime);
                }
                nFlush++;
                nFlush = nFlush % config.flushNumberOfBlocks();
            }
        });

        Block bestBlock = blockStore.getBestBlock();
        if (bestBlock == null) {
            logger.info("DB is empty - adding Genesis");

            BigInteger initialNonce = config.getBlockchainConfig().getCommonConstants().getInitialNonce();
            Genesis genesis = GenesisLoader.loadGenesis(config, config.genesisInfo(), initialNonce, true);
            for (RskAddress addr : genesis.getPremine().keySet()) {
                repository.createAccount(addr);
                InitialAddressState initialAddressState = genesis.getPremine().get(addr);
                repository.addBalance(addr, initialAddressState.getAccountState().getBalance());
                AccountState accountState = repository.getAccountState(addr);
                accountState.setNonce(initialAddressState.getAccountState().getNonce());

                if (initialAddressState.getContractDetails()!=null) {
                    repository.updateContractDetails(addr, initialAddressState.getContractDetails());
                    accountState.setStateRoot(initialAddressState.getAccountState().getStateRoot());
                    accountState.setCodeHash(initialAddressState.getAccountState().getCodeHash());
                }

                repository.updateAccountState(addr, accountState);
            }

            genesis.setStateRoot(repository.getRoot());
            genesis.flushRLP();

            blockStore.saveBlock(genesis, genesis.getCumulativeDifficulty(), true);
            blockchain.setBestBlock(genesis);
            blockchain.setTotalDifficulty(genesis.getCumulativeDifficulty());

            listener.onBlock(genesis, new ArrayList<TransactionReceipt>() );
            repository.dumpState(genesis, 0, 0, null);

            logger.info("Genesis block loaded");
        } else {
            BlockDifficulty totalDifficulty = blockStore.getTotalDifficultyForHash(bestBlock.getHash().getBytes());

            blockchain.setBestBlock(bestBlock);
            blockchain.setTotalDifficulty(totalDifficulty);

            logger.info("*** Loaded up to block [{}] totalDifficulty [{}] with stateRoot [{}]",
                    blockchain.getBestBlock().getNumber(),
                    blockchain.getTotalDifficulty().toString(),
                    Hex.toHexString(blockchain.getBestBlock().getStateRoot()));
        }

        String rootHash = config.rootHashStart();
        if (StringUtils.isNotBlank(rootHash)) {

            // update world state by dummy hash
            byte[] rootHashArray = Hex.decode(rootHash);
            logger.info("Loading root hash from property file: [{}]", rootHash);
            this.repository.syncToRoot(rootHashArray);

        } else {

            // Update world state to latest loaded block from db
            // if state is not generated from empty premine list
            // todo this is just a workaround, move EMPTY_TRIE_HASH logic to Trie implementation
            if (!Arrays.equals(blockchain.getBestBlock().getStateRoot(), EMPTY_TRIE_HASH)) {
                this.repository.syncToRoot(blockchain.getBestBlock().getStateRoot());
            }
        }
        return blockchain;
    }
}
