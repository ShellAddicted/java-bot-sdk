package im.dlg.botsdk;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import dialog.*;
import dialog.MessagingOuterClass.MessageContent;
import dialog.MessagingOuterClass.RequestSendMessage;
import dialog.MessagingOuterClass.UpdateMessage;
import im.dlg.botsdk.domain.Message;
import im.dlg.botsdk.domain.Peer;
import im.dlg.botsdk.domain.content.Content;
import im.dlg.botsdk.domain.content.DocumentContent;
import im.dlg.botsdk.domain.media.FileLocation;
import im.dlg.botsdk.light.MessageListener;
import im.dlg.botsdk.utils.Constants;
import im.dlg.botsdk.utils.MsgUtils;
import im.dlg.botsdk.utils.PeerUtils;
import im.dlg.botsdk.utils.UUIDUtils;

public class MessagingApi {

    private InternalBotApi privateBot;
    private MessageListener onMessage = null;


    MessagingApi(InternalBotApi privateBot) {
        this.privateBot = privateBot;

        privateBot.subscribeOn(UpdateMessage.class, msg -> {
            try {
                String _text = "";

                if (msg.getMessage().hasTextMessage()) {
                    _text = msg.getMessage().getTextMessage().getText();
                } else if (msg.getMessage().hasDocumentMessage()) {
                    _text = String.valueOf(msg.getMessage().getDocumentMessage().getFileId());
                }

                final String text = _text;

                privateBot.findOutPeer(msg.getPeer()).thenAccept(optOutPeer -> {
                    optOutPeer.ifPresent(outPeer -> {
                        privateBot.loadSenderOutPeer(msg.getSenderUid(), outPeer, msg.getDate())
                                .thenAcceptAsync(optSenderOutPeer ->
                                        optSenderOutPeer.ifPresent(senderOutPeer -> {
                                            final UUID uuid = UUIDUtils.convert(msg.getMid());
                                            onReceiveMessage(new Message(
                                                    PeerUtils.toDomainPeer(outPeer),
                                                    PeerUtils.toDomainPeer(senderOutPeer),
                                                    uuid, text, msg.getDate(), Content.fromMessage(msg.getMessage())));
                                        })
                                );
                    });
                });
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        });
    }

    /**
     * Subscribe to incoming messages
     *
     * @param listener - listener callback
     */
    public void onMessage(@Nullable MessageListener listener) {
        onMessage = listener;
    }

    /**
     * Send message to particular peer
     *
     * @param peer       - the address peer user/channel/group
     * @param message    - message content
     * @param targetUser - message will be visible only to this UID
     * @return - future with message UUID, that completes when deliver to server
     */
    public CompletableFuture<UUID> send(@Nonnull Peer peer, @Nonnull MessageContent message, @Nullable Integer targetUser) {
        Peers.OutPeer outPeer = PeerUtils.toServerOutPeer(peer);
        RequestSendMessage.Builder request = RequestSendMessage.newBuilder().setRid(MsgUtils.uniqueCurrentTimeMS())
                .setPeer(outPeer).setMessage(message);

        if (targetUser != null) {
            request.setIsOnlyForUser(targetUser);
        }

        return privateBot.withToken(
                MessagingGrpc.newFutureStub(privateBot.channel.getChannel())
                        .withDeadlineAfter(2, TimeUnit.MINUTES),
                stub -> stub.sendMessage(request.build())
        ).thenApplyAsync(resp -> UUIDUtils.convert(resp.getMid()), privateBot.executor.getExecutor());
    }

    /**
     * see #sendText
     */
    public CompletableFuture<UUID> sendText(@Nonnull Peer peer, @Nonnull String text) {
        return sendText(peer, text, null);
    }

    /**
     * Send plain text to particular peer
     *
     * @param peer       - the address peer user/channel/group
     * @param text       - text of message
     * @param targetUser - message will be visible only to this UID
     * @return - future with message UUID, that completes when deliver to server
     */
    public CompletableFuture<UUID> sendText(@Nonnull Peer peer, @Nonnull String text, @Nullable Integer targetUser) {
        MessageContent msg = MessageContent.newBuilder()
                .setTextMessage(MessagingOuterClass.TextMessage.newBuilder().setText(text).build())
                .build();
        return send(peer, msg, targetUser);
    }

    /**
     * see #sendMedia
     */
    public CompletableFuture<UUID> sendMedia(@Nonnull Peer peer,
                                             @Nonnull List<MessagingOuterClass.MessageMedia> medias) {
        return sendMedia(peer, medias, null);
    }

    /**
     * Send media message to particular peer
     *
     * @param peer       - the address peer user/channel/group
     * @param medias     - media attachments
     * @param targetUser - message will be visible only to this UID
     * @return - future with message UUID, that completes when deliver to server
     */
    public CompletableFuture<UUID> sendMedia(@Nonnull Peer peer,
                                             @Nonnull List<MessagingOuterClass.MessageMedia> medias,
                                             @Nullable Integer targetUser) {
        MessagingOuterClass.TextMessage.Builder textMessage = MessagingOuterClass.TextMessage
                .newBuilder();
        IntStream.range(0, medias.size())
                .forEach(index ->
                        textMessage.setMedia(index, medias.get(index)));

        MessageContent msg = MessageContent.newBuilder()
                .setTextMessage(textMessage.build())
                .build();
        return send(peer, msg, targetUser);
    }

    /**
     * see #sendDocument
     */
    public CompletableFuture<UUID> sendDocument(@Nonnull Peer peer,
                                                @Nonnull DocumentContent document) {
        return sendDocument(peer, document, null);
    }

    /**
     * Send document message to particular peer
     *
     * @param peer       - the address peer user/channel/group
     * @param document   - document/video attachment
     * @param targetUser - message will be visible only to this UID
     * @return - future with message UUID, that completes when deliver to server
     */
    public CompletableFuture<UUID> sendDocument(@Nonnull Peer peer,
                                             @Nonnull DocumentContent document,
                                             @Nullable Integer targetUser) {
        MessagingOuterClass.DocumentMessage documentMessage = DocumentContent.createDocumentMessage(document);

        MessageContent msg = MessageContent.newBuilder()
                .setDocumentMessage(documentMessage)
                .build();
        return send(peer, msg, targetUser);
    }

    /**
     * see #getFileUrl
     */
    public CompletableFuture<MediaAndFilesOuterClass.ResponseGetFileUrl> getFileUrl(@Nonnull FileLocation fileLocation) {
        return getFileUrl(fileLocation.getFileId(), fileLocation.getAccessHash());

    }

    /**
     * Get download url of a particular file
     *
     * @param fileId       - the address peer user/channel/group
     * @param accessHash   - document/video attachment
     * @return - future with (MediaAndFilesOuterClass.)ResponseGetFileUrl, that completes when deliver to server
     */
    public CompletableFuture<MediaAndFilesOuterClass.ResponseGetFileUrl> getFileUrl(@Nonnull long fileId, @Nonnull long accessHash) {

        MediaAndFilesOuterClass.RequestGetFileUrl.Builder request = MediaAndFilesOuterClass.RequestGetFileUrl.newBuilder().setFile(FileLocation.buildFileLocation(new FileLocation(fileId, accessHash)));

        return privateBot.withToken(
                MediaAndFilesGrpc.newFutureStub(privateBot.channel.getChannel())
                        .withDeadlineAfter(2, TimeUnit.MINUTES),
                stub -> stub.getFileUrl(request.build())
        ).thenApplyAsync(resp -> resp, privateBot.executor.getExecutor());
    }


    /**
     * Load history of messages in a chat
     *
     * @param peer      - peer chat
     * @param date      - date from
     * @param limit     - size of message batch
     * @param direction - direction
     * @return future with messages list
     */
    public CompletableFuture<List<Message>> load(Peer peer, long date, int limit, Direction direction) {

        MessagingOuterClass.RequestLoadHistory.Builder request =
                MessagingOuterClass.RequestLoadHistory.newBuilder()
                        .setPeer(PeerUtils.toServerOutPeer(peer))
                        .setDate(date).setLimit(limit)
                        .addAllOptimizations(Constants.OPTIMISATIONS);

        if (direction == Direction.FORWARD) {
            request.setLoadMode(MessagingOuterClass.ListLoadMode.LISTLOADMODE_FORWARD);
        } else if (direction == Direction.BACKWARD) {
            request.setLoadMode(MessagingOuterClass.ListLoadMode.LISTLOADMODE_BACKWARD);
        } else if (direction == Direction.BOTH) {
            request.setLoadMode(MessagingOuterClass.ListLoadMode.LISTLOADMODE_BOTH);
        }

        return privateBot.withToken(
                MessagingGrpc.newFutureStub(privateBot.channel.getChannel())
                        .withDeadlineAfter(2, TimeUnit.MINUTES),
                stub -> stub.loadHistory(request.build())
        ).thenApplyAsync(resp -> resp.getHistoryList().stream()
                .map(MsgUtils::toMessage).collect(Collectors.toList()));
    }

    /**
     * Marking a message and all previous as read
     *
     * @param peer - chat peer
     * @param date - date of message
     * @return a future
     */
    public CompletableFuture<Void> read(@Nonnull Peer peer, long date) {
        MessagingOuterClass.RequestMessageRead request = MessagingOuterClass.RequestMessageRead.newBuilder()
                .setPeer(PeerUtils.toServerOutPeer(peer)).setDate(date).build();

        return privateBot.withToken(
                MessagingGrpc.newFutureStub(privateBot.channel.getChannel())
                        .withDeadlineAfter(2, TimeUnit.MINUTES),
                stub -> stub.messageRead(request)
        ).thenApplyAsync(resp -> null, privateBot.executor.getExecutor());
    }

    private void onReceiveMessage(@Nonnull Message message) {
        if (onMessage != null) {
            onMessage.onMessage(message);
            return;
        }

        System.out.println("Got a message");
    }

    public enum Direction {
        FORWARD,
        BACKWARD,
        BOTH
    }
}
