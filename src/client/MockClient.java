package client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import api.BVerifyProtocolClientAPI;
import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.dictionary.AuthenticatedDictionaryClient;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetClient;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetPartial;
import pki.Account;
import serialization.BVerifyAPIMessageSerialization.Receipt;
import serialization.BVerifyAPIMessageSerialization.ReceiptIssueApprove;
import serialization.BVerifyAPIMessageSerialization.ReceiptRedeemApprove;
import serialization.BVerifyAPIMessageSerialization.ReceiptTransferApprove;
import serialization.BVerifyAPIMessageSerialization.Signature;
import serialization.MptSerialization.MerklePrefixTrie;

/**
 * For benchmarking and testing purposes 
 * we include a mock implementation of a 
 * b_verify client.
 * 
 * This client checks proofs and automatically
 * approves all requests if the proofs are valid. 
 * Unlike a real client, this client 
 * does not initiate requests. Request
 * initiation will be controlled
 * by the benchmarking framework.
 * Additionally the benchmarking framework 
 * promises to send requests to the correct clients
 * to make the code on the mock client simpler.
 * 
 * @author henryaspegren
 *
 */
public class MockClient implements BVerifyProtocolClientAPI{
	
	private final Account account;
	
	// stores server authentication information
	private AuthenticatedDictionaryClient authenticationInfo;
	
	// stores the ADSes that the client cares about
	// these the ADSes that hold the client receipts 
	private Map<byte[], AuthenticatedSetServer> clientReceipts;
	
	public MockClient(Account a) {
		this.account = a;
	}

	@Override
	public byte[] approveReceiptIssue(byte[] approveIssueMessage) {
		Signature.Builder sig = Signature.newBuilder();
		try {
			// parse the receipt issue request
			ReceiptIssueApprove request = ReceiptIssueApprove.parseFrom(approveIssueMessage);
			Receipt receipt = request.getReceipt();
			String recepientId = request.getRecepientId();
			String issuerId = request.getIssuerId();
			
			// de-serialize the proof
			MerklePrefixTrie proofProto = request.getAuthenticationProof();
			AuthenticatedDictionaryClient proof = MPTDictionaryPartial.deserialize(proofProto);
			
			// calculate ADS Key and lookup it up
			List<String> ids = new ArrayList<>();
			ids.add(recepientId);
			ids.add(issuerId);
			byte[] adsKey = CryptographicUtils.listOfAccountIDStringsToADSKey(ids);
			if(! this.clientReceipts.containsKey(adsKey) ) {
				return sig.build().toByteArray();
			}
			AuthenticatedSetServer ads = this.clientReceipts.get(adsKey);
			
			// perform some checks on the receipt
			// omitted - client automatically approves
			
			// commit to the receipt
			byte[] receiptCommitment = CryptographicUtils.witnessReceipt(receipt);
			
			// check that it is not currently in the ADS
			if(ads.inSet(receiptCommitment)) {
				return sig.build().toByteArray();
			}
			
			// and insert it into the ads
			ads.insert(receiptCommitment);
			byte[] newADSValue = ads.commitment();
			
			// check that the authentication information was updated
			// to reflect the new ads root
			byte[] proofADSValue = proof.get(adsKey);
			boolean updatedCorrectly = Arrays.equals(newADSValue, proofADSValue);
			if(!updatedCorrectly) {
				return sig.build().toByteArray();
			}			

			// if so sign!
			byte[] authRoot = proof.commitment();
			byte[] witness = CryptographicUtils.witnessUpdate(authRoot);
			byte[] sigBytes = CryptographicSignature.sign(witness, this.account.getPrivateKey());
			sig.setSignature(ByteString.copyFrom(sigBytes));
		} catch (InvalidProtocolBufferException | 
				InsufficientAuthenticationDataException | InvalidSerializationException e) {
			e.printStackTrace();	
		}
		return sig.build().toByteArray();
	}

	@Override
	public byte[] approveReceiptRedeem(byte[] approveRedeemMessage) {
		Signature.Builder sig = Signature.newBuilder();
		try {
			// parse the receipt issue request
			ReceiptRedeemApprove request = ReceiptRedeemApprove.parseFrom(approveRedeemMessage);
			byte[] receiptHash = request.getReceiptHash().toByteArray();
			String issuerId = request.getIssuerId();
			String ownerId = request.getOwnerId();
			
			// deserialize the proof
			MerklePrefixTrie proofProto = request.getAuthenticationProof();
			AuthenticatedDictionaryClient proof = MPTDictionaryPartial.deserialize(proofProto);
			
			// calculate and lookup the correct ads
			List<String> ids = new ArrayList<>();
			ids.add(ownerId);
			ids.add(issuerId);
			byte[] adsKey = CryptographicUtils.listOfAccountIDStringsToADSKey(ids);
			if(! this.clientReceipts.containsKey(adsKey) ) {
				return sig.build().toByteArray();
			}
			AuthenticatedSetServer ads = this.clientReceipts.get(adsKey);
			
			// check that the client has the receipt
			if(!ads.inSet(receiptHash)) {
				return sig.build().toByteArray();
			}
			
			// and remove it
			ads.delete(receiptHash);
			byte[] newADSValue = ads.commitment();
			
			// check that the authentication information was updated
			byte[] proofADSValue = proof.get(adsKey);
			boolean updatedCorrectly = Arrays.equals(newADSValue, proofADSValue);
			if(!updatedCorrectly) {
				return sig.build().toByteArray();
			}			
			
			// if so sign!
			byte[] authRoot = proof.commitment();
			byte[] witness = CryptographicUtils.witnessUpdate(authRoot);
			byte[] sigBytes = CryptographicSignature.sign(witness, this.account.getPrivateKey());
			sig.setSignature(ByteString.copyFrom(sigBytes));
		} catch (InvalidProtocolBufferException | 
				InsufficientAuthenticationDataException | InvalidSerializationException e) {
			e.printStackTrace();	
		}
		return sig.build().toByteArray();
	}

	@Override
	public byte[] approveReceiptTransfer(byte[] approveTransferMessage) {
		Signature.Builder sig = Signature.newBuilder();
		try {
			// parse the receipt issue request
			ReceiptTransferApprove request = ReceiptTransferApprove.parseFrom(approveTransferMessage);
			byte[] receiptHash = request.getReceiptHash().toByteArray();
			String issuerId = request.getIssuerId();
			String currentOwnerId = request.getCurrentOwnerId();
			String newOwnerId = request.getNewOwnerId();
			
			// de-serialize the various proofs
			MerklePrefixTrie addedProofProto = request.getAddedProof();
			MerklePrefixTrie removedProofProto = request.getRemovedProof();
			MerklePrefixTrie authProofProto = request.getAuthenticationProof();
			AuthenticatedSetClient addedProof = 
					MPTSetPartial.deserialize(addedProofProto);
			AuthenticatedSetClient removedProof = 
					MPTSetPartial.deserialize(removedProofProto);
			AuthenticatedDictionaryClient proof = 
					MPTDictionaryPartial.deserialize(authProofProto);

			// calculate and lookup the correct ads
			List<String> originADS = new ArrayList<>();
			originADS.add(issuerId);
			originADS.add(currentOwnerId);
			byte[] originADSKey = CryptographicUtils.listOfAccountIDStringsToADSKey(originADS);

			List<String> destinationADS = new ArrayList<>();
			destinationADS.add(issuerId);
			destinationADS.add(newOwnerId);
			byte[] destinationADSKey = CryptographicUtils.listOfAccountIDStringsToADSKey(destinationADS);

			// what needs to be check varies depending on which role the client 
			// is playing
			if(this.account.getId().equals(issuerId)) {
				if(!( this.clientReceipts.containsKey(originADSKey) && 
						this.clientReceipts.containsKey(destinationADSKey) ) ) {
					return sig.build().toByteArray();
				}
				AuthenticatedSetServer origin = this.clientReceipts.get(originADSKey);
				AuthenticatedSetServer destination = this.clientReceipts.get(destinationADSKey);
				if(!origin.inSet(receiptHash) || destination.inSet(receiptHash)) {
					return sig.build().toByteArray();
				}
				origin.delete(receiptHash);
				destination.insert(receiptHash);
				if(addedProof.commitment() != destination.commitment() ||
						removedProof.commitment() != origin.commitment()) {
					return sig.build().toByteArray();
				}
			}else if(this.account.getId().equals(currentOwnerId)) {
				if(!this.clientReceipts.containsKey(originADSKey) ) {
					return sig.build().toByteArray();
				}
				AuthenticatedSetServer origin = this.clientReceipts.get(originADSKey);
				if(!origin.inSet(receiptHash)) {
					return sig.build().toByteArray();
				}
				origin.delete(receiptHash);
				if(removedProof.commitment() != origin.commitment()) {
					return sig.build().toByteArray();
				}
				// check the proof that is has been added 
				if(!addedProof.inSet(receiptHash)) {
					return sig.build().toByteArray();
				}
			}else if(this.account.getId().equals(newOwnerId)) {
				if(!this.clientReceipts.containsKey(destinationADSKey) ) {
					return sig.build().toByteArray();
				}
				AuthenticatedSetServer destination = this.clientReceipts.get(destinationADSKey);
				if(destination.inSet(receiptHash)) {
					return sig.build().toByteArray();
				}
				destination.insert(receiptHash);
				if(addedProof.commitment() != destination.commitment()) {
					return sig.build().toByteArray();
				}
				// check the proof that is has been removed
				if(removedProof.inSet(receiptHash)) {
					return sig.build().toByteArray();
				}
			}else {
				throw new RuntimeException("sever sent request to the wrong client!");
			}
			byte[] newDestinationValue = addedProof.commitment();
			byte[] newOriginValue = removedProof.commitment();
			// check that these are updated in the auth proof
			byte[] proofDestinationADSValue = proof.get(destinationADSKey);
			byte[] proofOriginADSValue = proof.get(originADSKey);
			boolean updatedCorrectly = Arrays.equals(newDestinationValue, proofDestinationADSValue)
					&& Arrays.equals(newOriginValue, proofOriginADSValue);
			if(!updatedCorrectly) {
				return sig.build().toByteArray();
			}
			
			// if so sign!
			byte[] authRoot = proof.commitment();
			byte[] witness = CryptographicUtils.witnessUpdate(authRoot);
			byte[] sigBytes = CryptographicSignature.sign(witness, this.account.getPrivateKey());
			sig.setSignature(ByteString.copyFrom(sigBytes));
			
		} catch (InvalidProtocolBufferException | 
				InsufficientAuthenticationDataException | InvalidSerializationException e) {
			e.printStackTrace();	
		}
		return sig.build().toByteArray();
	}

}