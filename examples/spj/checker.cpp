#include <fstream>
#include <iostream>

// Accept whitespace around the integer, but no partial token or extra output.
bool oneInteger(std::istream& stream, long long& value) {
    char extra;
    return (stream >> value) && !(stream >> extra) && stream.eof();
}

int main(int argc, char** argv) {
    if (argc != 4) {
        std::cerr << "usage: checker <input> <contestant-output> <answer>\n";
        return 3;
    }
    std::ifstream input(argv[1]), output(argv[2]), answer(argv[3]);
    if (!input || !output || !answer) {
        std::cerr << "cannot open a judging file\n";
        return 3;
    }
    long long n, reference, value;
    if (!oneInteger(input, n) || n < 1 || !oneInteger(answer, reference)
            || reference < 1 || reference > n) {
        std::cerr << "invalid input or reference answer\n";
        return 3;
    }
    if (!oneInteger(output, value) || value < 1 || value > n) {
        std::cerr << "expected exactly one integer in [1, " << n << "]\n";
        return 1;
    }
    std::cerr << "accepted: " << value << '\n';
    return 0;
}
