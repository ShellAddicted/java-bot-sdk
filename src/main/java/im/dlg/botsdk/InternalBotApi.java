package im.dlg.botsdk;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StringValue;
import dialog.*;
import dialog.MessagingOuterClass.Dialog;
import dialog.MessagingOuterClass.HistoryMessage;
import dialog.MessagingOuterClass.ListLoadMode;
import dialog.MessagingOuterClass.RequestLoadDialogs;
import im.dlg.botsdk.domain.Message;
import im.dlg.botsdk.light.UpdateListener;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

class InternalBotApi implements StreamObserver<SequenceAndUpdatesOuterClass.SeqUpdateBox> {

    DialogExecutor executor;
    ChannelWrapper channel;

    private volatile Metadata metadata;
    private Map<Peers.Peer, Peers.OutPeer> outPeerMap = new ConcurrentHashMap<>();
    private static Integer appId = 11011;
    private String token;
    private Map<Integer, List<UpdateListener>> subscribers = new ConcurrentHashMap<>();


    InternalBotApi(String token, String host, int port, DialogExecutor executor) {
        this.token = token;
        this.executor = executor;
        this.channel = new ChannelWrapper(host, port);
    }

    public CompletableFuture<Void> start() {

        channel.connect();
        String deviceTitle = "BotWithToken" + token;
        CompletableFuture<Metadata> meta = new CompletableFuture<>();

        return executor.convert(
                RegistrationGrpc.newFutureStub(channel.getChannel()).registerDevice(
                        RegistrationOuterClass.RequestRegisterDevice.newBuilder()
                                .setAppId(appId)
                                .setAppTitle("BotSdk")
                                .setDeviceTitle(deviceTitle)
                                .build()
                )
        ).whenCompleteAsync((res, t) -> {
            if (res != null) {
                Metadata header = new Metadata();
                Metadata.Key<String> key = Metadata.Key.of("x-auth-ticket", Metadata.ASCII_STRING_MARSHALLER);
                header.put(key, res.getToken());

                meta.complete(header);
                metadata = header;
                System.out.println("Bot registered with token = " + res.getToken());
            } else if (t != null) {
                meta.completeExceptionally(t);
            }
        }).thenComposeAsync(res -> meta).thenComposeAsync(m ->
                executor.convert(withToken(m,
                        AuthenticationGrpc.newFutureStub(channel.getChannel()),
                        stub -> stub.startTokenAuth(
                                AuthenticationOuterClass.RequestStartTokenAuth.newBuilder()
                                        .setApiKey("BotSdk")
                                        .setAppId(appId)
                                        .setDeviceTitle(deviceTitle)
                                        .addPreferredLanguages("RU")
                                        .setTimeZone(StringValue.newBuilder().setValue("+3").build())
                                        .setToken(token)
                                        .build()
                        )
                )).thenApplyAsync(res -> new ImmutablePair<>(res.getUser(), m))
        ).thenApplyAsync(p -> {

            withToken(p.getRight(),
                    SequenceAndUpdatesGrpc.newStub(channel.getChannel()),
                    stub -> {
                        stub.seqUpdates(Empty.newBuilder().build(), this);
                        return new Object();
                    }
            );

            System.out.println("Bot authorized with id = " + p.getLeft().getId());

            return null;
        });
    }

    private void reconnect() {
        channel.connect();
        withToken(metadata, SequenceAndUpdatesGrpc.newStub(channel.getChannel()), stub -> {
            stub.seqUpdates(Empty.newBuilder().build(), this);
            return new Object();
        });
    }

    <T extends AbstractStub<T>, R> CompletableFuture<R> withToken(T stub, Function<T, ListenableFuture<R>> f) {
        T newStub = MetadataUtils.attachHeaders(stub, metadata);
        return executor.convert(f.apply(newStub));
    }

    <T extends AbstractStub<T>, R> R withToken(Metadata meta, T stub, Function<T, R> f) {
        T newStub = MetadataUtils.attachHeaders(stub, meta);
        return f.apply(newStub);
    }

    CompletableFuture<Optional<Peers.OutPeer>> findOutPeer(Peers.Peer peer) {
        return outPeerMap.containsKey(peer) ?
                CompletableFuture.completedFuture(Optional.of(outPeerMap.get(peer))) :
                // implicitly load outPeers of loaded dialogs
                loadDialogs(Sets.newHashSet(peer)).thenApplyAsync(x ->
                        outPeerMap.containsKey(peer) ? Optional.of(outPeerMap.get(peer)) : Optional.empty()
                );
    }

    CompletableFuture<Optional<Peers.OutPeer>> loadSenderOutPeer(Integer senderId, Peers.OutPeer peer, long date) {
        Peers.Peer senderPeer = PeerUtils.toUserPeer(senderId);
        return outPeerMap.containsKey(senderPeer) ?
                CompletableFuture.completedFuture(Optional.of(outPeerMap.get(senderPeer))) :
                // implicitly load outPeers of loaded dialogs
                load(peer, date, 2).thenApplyAsync(x ->
                        outPeerMap.containsKey(senderPeer) ? Optional.of(outPeerMap.get(senderPeer)) : Optional.empty()
                );
    }

    CompletableFuture<Optional<Peers.OutPeer>> findUserOutPeer(Integer userId) {
        Peers.Peer userPeer = PeerUtils.toUserPeer(userId);
        return findOutPeer(userPeer);
    }

    private void putOutPeer(Peers.OutPeer outPeer) {
        outPeerMap.put(PeerUtils.toPeer(outPeer), outPeer);
    }

    private void putOutPeer(Peers.UserOutPeer outPeer) {
        outPeerMap.put(PeerUtils.toPeer(outPeer), PeerUtils.toOutPeer(outPeer));
    }

    private void putOutPeer(Peers.GroupOutPeer outPeer) {
        outPeerMap.put(PeerUtils.toPeer(outPeer), PeerUtils.toOutPeer(outPeer));
    }

    public CompletableFuture<List<Message>> load(Peers.OutPeer peer, long from, Integer limit) {
        return withToken(
                MessagingGrpc.newFutureStub(channel.getChannel()),
                stub -> stub.loadHistory(MessagingOuterClass.RequestLoadHistory.newBuilder()
                        .setDate(from)
                        .setLimit(limit)
                        .setLoadMode(ListLoadMode.LISTLOADMODE_FORWARD)
                        .addAllOptimizations(RequestsDefaults.optimizations)
                        .setPeer(peer)
                        .build()
                )
        ).thenApplyAsync(res -> {
            res.getGroupPeersList().forEach(this::putOutPeer);
            res.getUserPeersList().forEach(this::putOutPeer);

            List<HistoryMessage> historyList = res.getHistoryList();
            List<Message> messages = new ArrayList<>();
            for (HistoryMessage hm : historyList) {
                putOutPeer(hm.getSenderPeer());
                messages.add(new Message(
                        PeerUtils.toDomainPeer(peer),
                        PeerUtils.toDomainPeer(hm.getSenderPeer()),
                        UUIDUtils.convert(hm.getMid()),
                        hm.getMessage().getTextMessage().getText()
                ));
            }

            return messages;
        }, executor.getExecutor());
    }

    CompletableFuture<List<Dialog>> loadDialogs(Set<Peers.Peer> peers) {
        RequestLoadDialogs request = RequestLoadDialogs.newBuilder()
                .setLimit(1)
                .addAllPeersToLoad(peers)
                .addAllOptimizations(RequestsDefaults.optimizations)
                .build();

        return withToken(
                MessagingGrpc.newFutureStub(channel.getChannel()),
                stub -> stub.loadDialogs(request)
        ).thenApplyAsync(res -> {
            res.getGroupPeersList().forEach(this::putOutPeer);
            res.getUserPeersList().forEach(this::putOutPeer);

            return res.getDialogsList();
        }, executor.getExecutor());
    }

    public synchronized void subscribeOn(int code, UpdateListener listener) {
        if (!subscribers.containsKey(code)) {
            subscribers.put(code, new CopyOnWriteArrayList<>());
        }

        subscribers.get(code).add(listener);
    }

    @Override
    public void onNext(SequenceAndUpdatesOuterClass.SeqUpdateBox value) {
        try {
            SequenceAndUpdatesOuterClass.UpdateSeqUpdate upd =
                    SequenceAndUpdatesOuterClass.UpdateSeqUpdate.parseFrom(value.getUpdate().getValue());

            int headerCode = upd.getUpdateHeader();
            if (subscribers.containsKey(headerCode)) {
                subscribers.get(headerCode).forEach(updateListener -> updateListener.onUpdate(upd.getUpdate()));
            } else {
                System.out.println("Unhandled update with header: " + headerCode);
            }

        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(Throwable t) {
        t.printStackTrace();
        reconnect();
    }

    @Override
    public void onCompleted() {
        System.out.println("onCompleted");
        reconnect();
    }
}
