# light-tram-kafka
light-tram-4j with Kafka transactional producer and consumer only

[Stack Overflow](https://stackoverflow.com/questions/tagged/light-4j) |
[Google Group](https://groups.google.com/forum/#!forum/light-4j) |
[Gitter Chat](https://gitter.im/networknt/light-tram-4j) |
[Subreddit](https://www.reddit.com/r/lightapi/) |
[Youtube Channel](https://www.youtube.com/channel/UCHCRMWJVXw8iB7zKxF55Byw) |
[Documentation](https://doc.networknt.com/style/light-tram-4j/) |
[Contribution Guide](https://doc.networknt.com/contribute/) |

This is a simplified light-tram-4j framework that only relies on Kafka without database and CDC dependencies. It is easy to understand and implement than the full-blown light-tram-4j framework. 

It is recommended using this framework if you can use Kafka 1.0 and above which supports exact once semantics. 

You need to choose the full-blown light-tram-4j if: 

* You are using an older version of Kafka that doesn't support the transaction. 
* You are using other message brokers.

At the moment, the light-tram-4j still depending on the light-eventuate-4j and it might be changed in the future to rely on the light-tram-kafka instead.
 
