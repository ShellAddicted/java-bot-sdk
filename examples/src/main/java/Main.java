import java.util.concurrent.ExecutionException;

import im.dlg.botsdk.Bot;
import im.dlg.botsdk.domain.content.Content;
import im.dlg.botsdk.domain.content.DocumentContent;
import im.dlg.botsdk.domain.content.TextContent;

public class Main {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        //
        Bot bot = Bot.start(System.getenv("DIALOGS_BOT_TOKEN")).get();

        bot.messaging().onMessage(message ->
                bot.users().get(message.getSender()).thenAccept(userOpt -> userOpt.ifPresent(user -> {
                            System.out.println("Got a message from user: " + user.getName() + " " + message.getMessageContent().toString());
                            Content cnt = message.getMessageContent();
                            if (cnt instanceof DocumentContent) {
                                bot.messaging().sendText(message.getPeer(), "File Received.");
                                bot.messaging().getFileUrl(((DocumentContent) message.getMessageContent()).getFileId(), ((DocumentContent) message.getMessageContent()).getAccessHash()).thenAccept(res -> {
                                    System.out.println("Received a file: " + res.getUrl() + " (timeout: " + res.getTimeout() + ") ");
                                });
                            } else if (cnt instanceof TextContent) {
                                System.out.println("Received Text: " + ((TextContent) message.getMessageContent()).getText());
                                bot.messaging().sendText(message.getPeer(), "Echo: " + ((TextContent) message.getMessageContent()).getText());
                            }
                        })
                ));

        bot.await();
    }
}