const List<String> nxQuotes = [
  "You don't have to be great to start. You have to start to be great.",
  "No excuses. Just results.",
  "The only bad workout is the one that didn't happen.",
  "Sore today, strong tomorrow.",
  "Discipline beats motivation every time.",
  "Small steps every day add up to big results.",
  "Your future self is watching. Make them proud.",
  "Sweat is just your fat crying.",
  "You're one workout away from a better mood.",
  "Don't count the days. Make the days count.",
  "Strength doesn't come from what you can do. It comes from overcoming what you couldn't.",
  "The pain you feel today is the strength you feel tomorrow.",
  "Excuses don't burn calories.",
  "Show up. Even when you don't feel like it. Especially then.",
  "A year from now you'll wish you started today.",
  "Champions train. Losers complain.",
  "It never gets easier. You just get stronger.",
  "Fall in love with the process and results will follow.",
  "Push yourself, because no one else is going to do it for you.",
  "Consistency is what transforms average into excellence.",
  "Wake up with determination. Go to bed with satisfaction.",
];

String quoteOfTheDay() {
  final dayOfYear =
      DateTime.now().difference(DateTime(DateTime.now().year)).inDays;
  return nxQuotes[dayOfYear % nxQuotes.length];
}
