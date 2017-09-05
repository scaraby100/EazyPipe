# EazyPipe

## Piping made eazy!

EazyPipe is a Java library that aims to make working in a multithreaded environment easier. It achieves that by providing a set of useful tools for starting threads and/or synchronizing them following the producer-consumer pattern.

# EazyPipe
## Pipe start object instantiation
EazyPipe start = new EazyPipe();

## Implementing pipeable methods: standard signature
public class YourProducerClass
{
  public void yourProducerMethod(Pipeable pipe)
  {
    ...
    pipe.output("I'm a producer");
  }
}

public class YourConsumerClass
{
  public void yourConsumerMethod(Pipeable pipe)
  {
    ...
    pipe.output(pipe.input() + ", and I'm a consumer!");
  }
}

## Pipeable object instantiation
YourProducerClass producer = new YourProducerClass();
YourConsumerClass consumer = new YourConsumerClass();

Pipeable pipeProd = new Pipeable(producer, "yourProducerMethod");
Pipeable pipeCons = new Pipeable(consumer, "yourConsumerMethod");

## Pipeline running
EasyPipe pipeOut = start.runPipe(pipeProd).runPipe(pipeCons);

## Output fetching example
ConcurrentLinkedQueue channel = pipeOut.getOutput();
while (true)
{
  String s = (String) channel.poll();
  if (s != null)
    System.out.println(s);
}

